/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.provider.CallLog
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.server.RecorderServerLauncher
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.system.storage.StorageRouter
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PhoneNumberManager
import com.baba.callvault.utils.RecordingFileNameFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * RecordingForegroundService is the long-running foreground service that
 * manage the audio-recording pipeline.
 *
 * The service has two visible states:
 *  - **Standby** – call is active but auto-record is disabled; a notification prompts the user.
 *  - **Recording** – audio pipeline is running; a "Stop" action is shown.
 *
 * @see <a href="https://developer.android.com/guide/components/foreground-services#background-start-restriction">Foreground Service Restrictions (Android 12+)</a>
 * @see <a href="https://developer.android.com/about/versions/14/changes/fgs-types-required">FGS Type Requirements (Android 14+)</a>
 */
class RecordingForegroundService : Service() {
    companion object {
        private const val TAG = "CV:RecordingForegroundService"

        // -- Intent action for controlling and initializing the service lifecycle. --

        /** Intent action sent to this service to initialize the service and immediately start a new recording session. */
        const val ACTION_START_RECORDING = "com.baba.callvault.START_RECORDING"

        /** Intent action sent to this service to initialize and prepare the recording session in standby mode. */
        const val ACTION_STANDBY = "com.baba.callvault.STANDBY"

        /** Intent action sent to this service to stop the current recording session and kill the service. */
        const val ACTION_STOP_RECORDING = "com.baba.callvault.STOP_RECORDING"

        // -- Intent action for controlling an active recording session with notifications. --

        /** Intent action sent to this service to pause the current recording. */
        const val ACTION_PAUSE_RECORDING = "com.baba.callvault.PAUSE_RECORDING"

        /** Intent action sent to this service to resume the current recording. */
        const val ACTION_RESUME_RECORDING = "com.baba.callvault.RESUME_RECORDING"


        /**
         * Intent action sent by the standby notification's "Record" button.
         */
        const val ACTION_MANUAL_START = "com.baba.callvault.MANUAL_START_RECORDING"

        /** Intent action sent to this service when the user dismisses the notification (Android 14+). */
        const val ACTION_NOTIFICATION_DISMISSED = "com.baba.callvault.SERVICE_NOTIFICATION_DISMISSED"
    }

    // ── Dependencies ──────────────────────────────────────────────────────────
    private lateinit var appPreferences: AppPreferences

    private lateinit var phoneNumberManager: PhoneNumberManager

    private lateinit var notificationHelper: RecordingNotificationHelper

    /** Scope for service lifecycle operations (binding, etc.) */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Recording session state ────────────────────────────────────────────────────────

    /** The current state of the service. */
    @Volatile
    private var currentState: RecordingServiceState = RecordingServiceState.Standby(null)
        set(value) {
            if (field != value) {
                val oldState = field
                field = value
                updateNotification()
                notificationHelper.handleStateChangeToasts(oldState, value)
            }
        }

    /**
     * Set true the moment a STOP is requested (call ended / explicit stop / service destroy). The
     * recording start path runs on a background coroutine and can block for tens of seconds while the
     * daemon cold-starts; a STOP that arrives during that window would otherwise be a no-op (no Active
     * engine yet), and the late start would turn the mic on AFTER the call with nothing to stop it. The
     * start path checks this flag (passed into [AudioRecordingEngine.startPipeline]) to abort, and a
     * belt-and-suspenders check tears down if the start completed just after the stop.
     */
    @Volatile
    private var stopRequested: Boolean = false

    /**
     * True once the mid-call daemon-death warning has been shown for the current session. The
     * end-of-call empty-file check consults this so the same failure doesn't raise a second error
     * notification that overwrites the first (both share the error notification ID).
     */
    @Volatile
    private var daemonLossNotified: Boolean = false

    /** True while a recording session object is present (initializing, active, or pending teardown). */
    private val hasSession: Boolean
        get() = currentState is RecordingServiceState.Active

    /** True only if the pipeline is actively reading and capturing audio. */
    private val isCurrentlyRecording: Boolean
        get() = (currentState as? RecordingServiceState.Active)?.engine?.let { e ->
            // Local mode: the pipe-read job reflects liveness. Daemon mode: there is no local job, so
            // consult the daemon-recording flag instead (else this is always false while the daemon records).
            e.audioPipeReadJob?.isActive == true || e.daemonRecording
        } == true

    // ── Service lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationHelper = RecordingNotificationHelper(this)
        notificationHelper.createNotificationChannels()

        appPreferences = AppPreferences(this)
        phoneNumberManager = PhoneNumberManager.getInstance(this)

        AppLogger.d(TAG, "RecordingForegroundService initialized")
    }

    // No binding. This is a fully command-based service. All interactions are via startService with intent actions.
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Entry point for intent-based commands (START / MANUAL_START / STOP).
     *
     * Returns [Service.START_NOT_STICKY] so Android does not auto-restart the service after a process
     * kill; recording must always be explicitly triggered by an active call.
     *
     * @param intent  The intent carrying the action constant.
     * @param flags   Standard Android service start flags.
     * @param startId Unique ID for this start request (not used; state is managed manually).
     * @return [Service.START_NOT_STICKY].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        var currentMeta = currentState.metadata

        // Parse metadata if present in the intent (START/STANDBY)
        if (intent != null) {
            val newMetadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    RecordingMetadata.EXTRA_METADATA,
                    RecordingMetadata::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(RecordingMetadata.EXTRA_METADATA)
            }
            if (newMetadata != null) {
                currentMeta = newMetadata
            }
        }

        // Quickly show a notification to satisfy Android's foreground service requirements,
        // as starting/waiting for the privileged daemon can take long enough for the OS to kill the service.
        updateNotification()

        when (action) {
            ACTION_START_RECORDING, ACTION_MANUAL_START -> {
                if (hasSession || isCurrentlyRecording) {
                    AppLogger.w(TAG, "Start request ignored. A session is already on-going.")
                    return START_NOT_STICKY
                }

                // At this point, we should already have the metadata from the intents, if it's missing, there's a logic error to be fixed.
                if (currentMeta == null) {
                    AppLogger.e(TAG, "Start request received without metadata. Cannot start recording session.")
                    notificationHelper.showErrorNotification(getString(R.string.recording_unexpected_error))
                    stopRecordingSessionAndService()
                    return START_NOT_STICKY // We won't reach this anyway.
                }

                stopRequested = false
                currentState = RecordingServiceState.Starting(currentMeta)

                // IO dispatcher: AdbShell.ensureConnected + ScrcpyLauncher do real network I/O over the
                // ADB TLS socket (and block on a socket-readiness retry loop). Running on the main thread
                // throws NetworkOnMainThreadException and would ANR.
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        // Recording always goes through the persistent daemon (commanded over binder, no ADB
                        // at record time — so we must NOT ensureConnected here, which would re-enable WD).
                        // AudioRecordingEngine ensures the daemon (transiently enabling WD only to relaunch it)
                        // and itself falls back to a direct ADB connection if the daemon is truly unavailable.
                        startNewRecordingSession(currentMeta)
                    } catch (e: Exception) {
                        // Don't catch coroutine cancellations, they are used for cleanup. This creates a false error notification when everything's fine.
                        if (e is CancellationException) throw e

                        AppLogger.e(TAG, "Failed to start recording session.", e)
                        // Don't assert a cause here — this generic catch sees ALL start failures (daemon,
                        // ADB, storage, …). Prefixing "ADB connection failed" mislabels e.g. a SAF storage
                        // error as an ADB problem (real field report: "Unsupported mode: rw"). Just surface
                        // the technical detail; specific causes are reported via PipelineInitializationException.
                        notificationHelper.showErrorNotification(getString(R.string.recording_error_start_failed) + "\n" + e.localizedMessage)
                        stopRecordingSessionAndService()
                    } finally {
                        if (currentState is RecordingServiceState.Starting) {
                            if (!hasSession) {
                                currentState = RecordingServiceState.Standby(currentMeta)
                            }
                        }
                    }
                }
            }

            ACTION_STANDBY -> {
                currentState = RecordingServiceState.Standby(currentMeta)
                serviceScope.launch(Dispatchers.IO) {
                    // Warm the persistent daemon at ring/dial so the call records instantly over binder.
                    // ensureServerRunning is a no-op when the binder is already connected (WD untouched); it
                    // only transiently re-enables WD to relaunch the daemon if it died, then turns WD back off.
                    AppLogger.i(TAG, "Entered standby for ${currentMeta?.direction} call; ensuring recorder daemon is running")
                    RecorderServerLauncher.ensureServerRunning(this@RecordingForegroundService)
                }
            }

            ACTION_PAUSE_RECORDING -> {
                (currentState as? RecordingServiceState.Active)?.let {
                    it.engine.isPaused = true
                    currentState = it.copy(isPaused = true)
                }
            }

            ACTION_RESUME_RECORDING -> {
                (currentState as? RecordingServiceState.Active)?.let {
                    it.engine.isPaused = false
                    currentState = it.copy(isPaused = false)
                }
            }

            ACTION_STOP_RECORDING -> {
                // Mark stop FIRST so an in-flight start (blocked in the daemon cold-start) aborts instead
                // of turning the mic on after the call has ended.
                stopRequested = true
                stopRecordingSessionAndService()
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                AppLogger.d(TAG, "Ongoing foreground service notification dismissed by user (Android 14+), reposting.")
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Always clean up, even if the OS kills the service mid-recording.
        // This is the guaranteed last callback before the service process is cleaned up.
        AppLogger.v(TAG, "RecordingForegroundService is destroying... Ensuring cleanup...")
        stopRequested = true
        serviceScope.cancel()
        stopRecordingSessionAndService()
        super.onDestroy()
    }

    // ── Service internal logic ───────────────────────────────────────
    /**
     * Orchestrates the recording state at the Service level.
     * Creates a new [AudioRecordingEngine], starts the I/O pipeline, updates the visible notification,
     * and handles fatal [PipelineInitializationException].
     */
    private fun startNewRecordingSession(metadata: RecordingMetadata) {
        if (hasSession) {
            AppLogger.w(TAG, "startNewRecordingSession() called while already active – ignoring")
            return
        }

        // 1. Create a new session (declared here to allow cleanup if startPipeline fails)
        val activeSession = AudioRecordingEngine()
        daemonLossNotified = false
        // Surface a mid-call daemon death immediately — the pipeline "started successfully" only
        // proves the dispatch worked, and a daemon that dies right after leaves an empty file.
        activeSession.onDaemonLostDuringRecording = {
            daemonLossNotified = true
            notificationHelper.showErrorNotification(getString(R.string.recording_error_daemon_died))
        }

        try {
            // 2. Try to start the pipeline. Pass our stop flag so the (slow, daemon-cold-start) start
            //    aborts before touching the daemon if the call already ended — preventing a mic that
            //    turns on after the call with nothing to stop it.
            activeSession.startPipeline(this, metadata) { stopRequested }
            // 3. Success
            currentState = RecordingServiceState.Active(activeSession, false, metadata)
            AppLogger.i(TAG, "Recording pipeline started successfully")
            // Belt-and-suspenders: if a STOP landed in the tiny window after the daemon accepted the
            // recording but before we reached here, tear it down immediately so capture (and the mic)
            // does not linger past the call.
            if (stopRequested) {
                AppLogger.w(TAG, "Stop requested during startup; tearing down the just-started session")
                stopRecordingSessionAndService()
            }
        } catch (e: PipelineInitializationException) {
            AppLogger.e(TAG, e.message ?: "", e.cause ?: e)
            notificationHelper.showErrorNotification(e.userFriendlyMessage)
            // Ensure partial resources are cleaned up
            activeSession.cancel(this)
            currentState = RecordingServiceState.Standby(metadata)
            stopRecordingSessionAndService()
        }
    }

    /**
     * Stops the current recording session and stop the foreground recording service.
     *
     * Always trigger [AudioRecordingEngine.release] so that if we are currently recording,
     * we safely shuts down the pipeline and saves the file, clears the current session,
     * removes the foreground notification, and stops the service.
     */
    private fun stopRecordingSessionAndService() {
        val activeSession = (currentState as? RecordingServiceState.Active)?.engine
        if (activeSession == null) {
            AppLogger.d(TAG, "No active session, exiting standby state, removing foreground notification and stopping service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf() // Stop the service since the session is over
            return
        }
        AppLogger.i(TAG, "Stopping active recording session, remove foreground notification and stopping service...")

        // Capture metadata before releasing resources, in case we need to query call logs for the final file name if phone number is empty.
        val originalMetadata = activeSession.initializationMetadata
        val uriToRename = activeSession.currentRecordingUri
        // Capture mimeType before release (currentCodecEnum is not cleared by release()).
        val mimeType = activeSession.currentCodecEnum.mimeType

        // Release all resources held by the recording session, stopping the ADB transport and finalizing the recording file.
        activeSession.release()

        // If the initialization metadata do not contain a phone number, we attempt to query the call log as a fallback.
        // TODO: Remove this fallback logic once we have a more reliable way to get phone number (using a privileged shell and hidden api)
        if (originalMetadata != null && originalMetadata.rawPhoneNumber.isNullOrBlank() && uriToRename != null) {
            AppLogger.d(TAG, "Recording ended without a phone number. Querying CallLog as a fallback to get more information...")
            // We use GlobalScope/IO because the Service's scope might be cancelled immediately in onDestroy.
            // Android gives the process some time to live, so this is safe for a few seconds.
            CoroutineScope(Dispatchers.IO).launch {
                val rawNumber =
                    tryGetFinalNumberFromLog(applicationContext, originalMetadata.direction)
                val sanitizedRaw = PhoneNumberManager.sanitizeOemNumber(rawNumber) ?: ""

                val finalNumber = if (sanitizedRaw.isNotBlank()) {
                    val parsed = phoneNumberManager.parsePhoneNumber(sanitizedRaw)
                    if (parsed != null) {
                        phoneNumberManager.formatToE164(parsed)
                    }
                    sanitizedRaw
                } else {
                    sanitizedRaw
                }

                // finalUri tracks the URI after any rename attempt, for routing exactly once.
                val finalUri: Uri
                if (finalNumber.isNotBlank()) {
                    val updatedMeta = originalMetadata.copy(rawPhoneNumber = finalNumber)
                    val newName = RecordingFileNameFormatter.formatFileName(applicationContext, updatedMeta, activeSession.currentCodecEnum)
                    // Use DocumentsContract.renameDocument (NOT DocumentFile.renameTo): the latter is a
                    // SingleDocumentFile here and throws UnsupportedOperationException, while
                    // renameDocument is supported by ExternalStorageProvider and returns the new URI.
                    var renamedUri: Uri? = null
                    try {
                        renamedUri = DocumentsContract.renameDocument(applicationContext.contentResolver, uriToRename, newName)
                        if (renamedUri != null) {
                            AppLogger.d(TAG, "Successfully renamed wrongly detected anonymous recording to: $newName")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to rename file using CallLog fallback", e)
                    }
                    // renameDocument may return a new document URI; fall back to the original on failure.
                    finalUri = renamedUri ?: uriToRename
                } else {
                    AppLogger.d(TAG, "Call log confirmed the call is anonymous, or no actual number was found. Keeping file name as is.")
                    finalUri = uriToRename
                }

                // Route exactly once, after any rename has been applied.
                routeFinalRecording(finalUri, mimeType)
            }
        } else if (uriToRename != null) {
            // Phone number was already known at recording start — no rename needed.
            // Route the file with its current name immediately (once).
            routeFinalRecording(uriToRename, mimeType)
        }
        currentState = RecordingServiceState.Standby(null)
        AppLogger.i(TAG, "The recording session has been stopped and resources have been released. Stopping foreground service. Goodbye >3")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf() // Stop the service since the session is over
    }

    /**
     * Tries to query the call log for the most recent call matching the given direction, and returns the associated phone number.
     * @return The phone number from the most recent call log entry matching the direction, or null if no valid entry is found after multiple attempts.
     */
    private suspend fun tryGetFinalNumberFromLog(
        context: Context,
        direction: RecordingDirection?
    ): String? {
        val typeSelection = when (direction) {
            // We do not want to include missed or rejected calls here since they are useless to us, and in a Dual-call scenario could lead to picking the wrong number.
            RecordingDirection.INCOMING -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}"
            RecordingDirection.OUTGOING -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
            else -> null
        }
        // Try multiples times with a delay in case the OS didn't write the call log entry yet (only written after the call ended).
        for (i in 1..4) {
            try {
                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER),
                    typeSelection, null,
                    "${CallLog.Calls.DATE} DESC"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        return it.getString(0)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to query call log for fallback number", e)
            }
            if (i < 4) delay(400)
        }
        return null
    }

    /**
     * Routes the final (post-rename) recording file to the configured storage destination via [StorageRouter].
     * Resolves the display name from the DocumentFile; if the DocumentFile cannot be resolved the call is a no-op.
     *
     * @param uri      The URI of the finalized recording file.
     * @param mimeType The MIME type of the recording (e.g. "audio/opus" or "audio/mp4a-latm").
     */
    private fun routeFinalRecording(uri: Uri, mimeType: String) {
        val doc = DocumentFile.fromSingleUri(applicationContext, uri) ?: return
        val name = doc.name ?: return
        val sizeBytes = doc.length()
        val lastModified = doc.lastModified()
        // An empty file means capture never actually ran (typically: the daemon died right after
        // startRecording was dispatched — seen when Developer options is off and Wireless debugging
        // teardown kills the daemon). Surface an honest failure instead of cataloging an unplayable
        // entry and copying zero bytes to Drive.
        if (sizeBytes <= 0L) {
            AppLogger.e(TAG, "Recording '$name' is empty (0 bytes) — capture never ran. Deleting it and notifying instead of cataloging.")
            // The mid-call daemon-death warning is the more actionable message and shares the same
            // notification ID — don't overwrite it (and double-vibrate) with the empty-file variant.
            if (!daemonLossNotified) {
                notificationHelper.showErrorNotification(getString(R.string.recording_error_empty_file))
            }
            runCatching { doc.delete() }
                .onFailure { AppLogger.w(TAG, "Failed to delete empty recording '$name': ${it.message}") }
            return
        }
        // Record this finished recording in CallVault's own catalog (the Home list's source of truth).
        // The file is on the device now, so this is the local copy; the copy/sweep workers later stamp
        // the Drive copy onto this same row by name. Done on a detached IO scope because the service may
        // be torn down right after (the process is given a few seconds to live — same rationale as the
        // CallLog fallback above). Written for every storage target, regardless of where the file lands.
        CoroutineScope(Dispatchers.IO).launch {
            RecordingCatalog.recordLocal(applicationContext, name, uri, sizeBytes, lastModified)
        }
        StorageRouter.route(applicationContext, uri, name, mimeType)
    }

    /**
     * Updates the foreground service notification based on the current state (Recording or Standby).
     */
    private fun updateNotification() {
        val notification = notificationHelper.getNotification(currentState)
        startForegroundWithType(notification)
    }


    /**
     * Calls [startForeground] with the appropriate [ServiceInfo] foreground service type.
     * Uses [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] on API 34+ as required by
     * Android 14's FGS type enforcement; falls back to DATA_SYNC on API 30-33.
     *
     * @param notification The notification to display while in the foreground.
     */
    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse is the best type to use from Android 14+ for our call recording use-cases.
            // See: https://developer.android.com/about/versions/14/changes/fgs-types-required#special-use
            startForeground(
                RecordingNotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android 11-13 uses the not yet restricted Data Sync type.
            // Starting Android 15, dataSync type is restricted to a total of 6 hours runtime in a specific time period.
            startForeground(
                RecordingNotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }
}
