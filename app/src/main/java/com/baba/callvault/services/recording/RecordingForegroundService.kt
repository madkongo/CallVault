/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.aftercall.AfterCallDecision
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.calls.PhoneCallMatch
import com.baba.callvault.data.calls.PhoneCallReader
import com.baba.callvault.data.calls.SimLabels
import com.baba.callvault.data.health.CallOutcomes
import com.baba.callvault.data.health.CapturedCaptureLevels
import com.baba.callvault.data.health.SetupFingerprint
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.data.health.record
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.server.CaptureLevels
import com.baba.callvault.server.CaptureSilence
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.R
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.waveform.WaveformDrawWorker
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.StorageRouter
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.HONOR_DIALER_TEMPLATE
import com.baba.callvault.utils.HonorCallStart
import com.baba.callvault.utils.PhoneNumberManager
import com.baba.callvault.utils.RedirectedCall
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.RecordingFileNameFormatter
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.storage.FreeSpaceMonitor
import com.baba.callvault.system.storage.FreeSpacePolicy
import com.baba.callvault.system.storage.MinDurationPolicy
import com.baba.callvault.system.storage.WaitingNote
import com.baba.callvault.system.storage.WaitingRecordings
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.transcripts.FlagRepository
import com.baba.callvault.data.transcripts.CapturedSpeakerTurns
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.handoff.HandoffReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import java.util.Date

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
        /** Reads of the call log while waiting for the just-ended call's entry (Honor preset, #56): 8 × 500 ms. */
        private const val HONOR_LOG_ATTEMPTS = 8
        private const val HONOR_LOG_RETRY_MS = 500L
        /** Tries at [HONOR_LOG_RETRY_MS] for this call's entry: it is written as the call ends, normally within a second. */
        private const val REDIRECT_LOG_ATTEMPTS = 6

        /** Slack for a switch queued inside [WifiCallRoute.SWITCH_WINDOW_MS] to reach the release. */
        private const val SWITCH_QUEUE_GRACE_MS = 1_000L
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

        /** Marks the current moment in the recording, from the ongoing notification. */
        const val ACTION_FLAG_MOMENT = "com.baba.callvault.FLAG_MOMENT"


        /**
         * Intent action sent by the standby notification's "Record" button.
         */
        const val ACTION_MANUAL_START = "com.baba.callvault.MANUAL_START_RECORDING"

        /** Intent action sent to this service when the user dismisses the notification (Android 14+). */
        const val ACTION_NOTIFICATION_DISMISSED = "com.baba.callvault.SERVICE_NOTIFICATION_DISMISSED"

        /** True while the carrier call is recorded through the app-call capture (#45). */
        @Volatile
        var isAppMixSession: Boolean = false
            private set
    }

    // ── Dependencies ──────────────────────────────────────────────────────────
    private lateinit var appPreferences: AppPreferences

    private lateinit var phoneNumberManager: PhoneNumberManager

    private lateinit var notificationHelper: RecordingNotificationHelper

    /** Scope for service lifecycle operations (binding, etc.) */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Recording session state ────────────────────────────────────────────────────────

    /**
     * Set once the service has begun stopping, so nothing re-posts the notification it is removing.
     *
     * Without it, the stop path's own `currentState = Standby` re-posted "Call in progress — Press to
     * start recording" for a moment at the end of every recording, and a failed start's `finally`
     * re-promoted a service that had already called stopSelf (issue #31). Cleared by the next command.
     */
    @Volatile
    private var isShuttingDown = false

    /**
     * Whether this service's notification takes the place of "Ready to record calls" ([SharedStatusNotice]),
     * so a recorded call shows one notification instead of two.
     *
     * Not for a start ignored because the VoIP recorder already has the call: that notification is the
     * keep-alive's own VoIP recording notice, and replacing it with a carrier prompt would hide it.
     */
    private var sharesStatusNotice = true

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
     * Position in the saved audio, for marks placed from the notification. Excludes paused time —
     * see [RecordingClock].
     */
    private val recordingClock = RecordingClock()

    /**
     * True once the mid-call daemon-death warning has been shown for the current session. The
     * end-of-call empty-file check consults this so the same failure doesn't raise a second error
     * notification that overwrites the first (both share the error notification ID).
     */
    @Volatile
    private var daemonLossNotified: Boolean = false

    // ── Wi-Fi call route (#45) ─────────────────────────────────────────────────────

    private var routeWatcher: WifiCallRouteWatcher? = null

    /** One-way latch: set when the app-call capture is chosen, cleared only by the next START. */
    @Volatile
    private var switchedToMix = false

    @Volatile
    private var lastRouteObservation: WifiCallRoute.Observation? = null

    /** This call's mode was seen in IN_CALL — the first half of the Samsung Wi-Fi-call flip (#45). */
    private var inCallSeenThisCall = false

    /** When the carrier capture started (elapsedRealtime); the switch window counts from here. */
    @Volatile
    private var captureStartedAt = 0L

    /** Guards [switchInProgress] and [stopDuringSwitch]; held only for a moment, never across I/O. */
    private val switchLock = Any()
    private var switchInProgress = false
    private var stopDuringSwitch = false

    /** True while a recording session object is present (initializing, active, or pending teardown). */
    private val hasSession: Boolean
        get() = currentState is RecordingServiceState.Active

    /**
     * Whether the VoIP recorder already owns this call, so the carrier path must stand down.
     *
     * **Deliberately inert unless VoIP recording is switched on.** A carrier recording that is wrongly
     * suppressed is a lost call — far worse than a spurious error notification — so a user who has not
     * enabled the VoIP feature keeps exactly today's behaviour, whatever their phone reports.
     *
     * With the feature on, two signals are accepted: our own VoIP recorder already running, and the
     * audio mode being `MODE_IN_COMMUNICATION`. The mode is the same discriminator the VoIP detector
     * itself uses (carrier calls are `MODE_IN_CALL`), and it covers the race where the telephony state
     * arrives before the VoIP recording has started — measured at ~230 ms on One UI.
     */
    private fun isVoipCallInProgress(): Boolean = RecordingPolicy.standsDownForAppCall(
        voipEnabled = AppPreferences(this).isVoipRecordingEnabled(),
        voipRecording = VoipRecordingCoordinator.isRecording,
        // Asked of Telecom rather than of the audio mode: an IMS or Wi-Fi call can sit in
        // MODE_IN_COMMUNICATION too, and standing down for one loses the recording entirely.
        carrierCallUp = VoipTelephonyGate.managedCallInProgress(this),
        modeIsInCommunication = runCatching {
            getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)
    )

    /** True only if the pipeline is actively reading and capturing audio. */
    private val isCurrentlyRecording: Boolean
        get() = (currentState as? RecordingServiceState.Active)?.engine?.let { e ->
            // Local mode: the pipe-read job reflects liveness. Daemon mode / resilient handoff mode: there
            // is no local job, so consult the mode flags instead (else this is always false while recording).
            e.audioPipeReadJob?.isActive == true || e.daemonRecording || e.handoffRecording
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
    /** Its error texts follow the app's language, not only the phone's (BabApps item 123). */
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LocalizedContext.of(newBase))

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
            // IntentCompat, not the typed platform call. The typed `getParcelableExtra(name, Class)`
            // has a platform bug on Android 13 (T) where it returns null for a custom Parcelable
            // even though the extra is present, because the extras are unparcelled with the wrong
            // class loader. Here that meant the metadata silently fell back to the previous value:
            // a recording could carry the wrong call's details, or fail to stop when the call ended.
            //
            // The androidx shim picks the working path per API level, so this is the one call that
            // is correct on every version. Fixed upstream in ShizuCallRecorder for the same reason.
            val newMetadata = IntentCompat.getParcelableExtra(
                intent,
                RecordingMetadata.EXTRA_METADATA,
                RecordingMetadata::class.java,
            )
            if (newMetadata != null) {
                currentMeta = newMetadata
            }
        }

        // Whether this command posts first, just acts, or has no call behind it at all. See
        // [RecordingCommandPolicy]: a STOP that posted on its way out could land after "Ready" and stay in the
        // shade, and every action on that leftover started a service with no call that never went away.
        val hasCall = hasSession || isCurrentlyRecording || currentMeta != null
        // Asked once, with the opening post, so the notice and the decision below cannot disagree.
        var voipCall = false
        when (RecordingCommandPolicy.handling(action, hasCall)) {
            RecordingCommandPolicy.Handling.END_ORPHAN -> {
                AppLogger.i(TAG, "'$action' arrived with no call in progress; ending this service instead of re-posting")
                val claimed = SharedStatusNotice.isHeldBy(SharedStatusNotice.Owner.CARRIER)
                stopRecordingSessionAndService()
                // Nothing was claimed by this instance, so the release above told nobody — but a leftover
                // recording notification may be what the user just touched. Put "Ready" back regardless.
                if (!claimed) SharedStatusNotice.requestRefresh()
                return START_NOT_STICKY
            }
            RecordingCommandPolicy.Handling.HANDLE_ONLY -> {
                // A new command means this instance is in use again, even if it had started to stop.
                isShuttingDown = false
            }
            RecordingCommandPolicy.Handling.POST_THEN_HANDLE -> {
                isShuttingDown = false
                voipCall = postOpeningNotice(action, currentMeta)
            }
        }

        when (action) {
            ACTION_START_RECORDING, ACTION_MANUAL_START -> {
                if (hasSession || isCurrentlyRecording) {
                    AppLogger.w(TAG, "Start request ignored. A session is already on-going.")
                    return START_NOT_STICKY
                }

                // A VoIP call can raise the TELEPHONY state too, so this carrier path is asked to record
                // a call the VoIP path is already recording. Seen on One UI: a WhatsApp call reported
                // OFFHOOK, this service created a second file (named from the CALL LOG, so with an
                // unrelated contact's name), the daemon refused with "already recording", and the empty
                // file became an error notification — while the VoIP recording itself was perfectly fine.
                // Checked here rather than at OFFHOOK because the telephony state arrives ~230 ms BEFORE
                // the VoIP call is detected, so there is nothing to see yet at that point.
                if (voipCall) {
                    // Stand down AND stop: the VoIP recorder owns this call, so this carrier service
                    // has nothing to record. Leaving it running kept its benign opening notice up as a
                    // foreground service for the whole call — and before the stand-aside notice, that
                    // was the actionable "Call in progress — Press to start recording" (#54). The
                    // no-session path below removes the foreground notice, hands "Ready" back to the
                    // keep-alive, and stops the service.
                    AppLogger.i(TAG, "Start request ignored: VoIP call, already handled by the VoIP recorder; standing the carrier service down (#54)")
                    stopRecordingSessionAndService()
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
                    RecorderBackend.ensureRunning(this@RecordingForegroundService)
                }
            }

            ACTION_PAUSE_RECORDING -> {
                (currentState as? RecordingServiceState.Active)?.let {
                    it.engine.isPaused = true
                    currentState = it.copy(isPaused = true)
                    // The clock has to follow, or every mark placed after a pause lands late in the
                    // finished file by exactly the length of that pause.
                    recordingClock.pause()
                }
            }

            ACTION_RESUME_RECORDING -> {
                (currentState as? RecordingServiceState.Active)?.let {
                    it.engine.isPaused = false
                    currentState = it.copy(isPaused = false)
                    recordingClock.resume()
                }
            }

            ACTION_FLAG_MOMENT -> {
                val at = recordingClock.audioElapsedMs()
                if (at == null) {
                    AppLogger.w(TAG, "Flag pressed with no recording running; ignoring.")
                } else {
                    PendingFlags.add(at)
                    // Re-post so the button's own label shows the new count. A button that appears
                    // to do nothing gets pressed again, which is how one moment becomes five marks.
                    updateNotification()
                    // The toast as well, for the case where the shade is already closed. It is not
                    // relied on: with the shade open it renders behind it and is never seen, which
                    // is exactly what was reported.
                    notificationHelper.showFlagToast(PendingFlags.count())
                    AppLogger.i(TAG, "Marked ${at}ms into the recording (${PendingFlags.count()} so far).")
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

    /**
     * Posts the notification a foreground start must show straight away. It describes the state this command
     * is about to enter, not the one the service is in: a freshly started service is in Standby, and posting
     * that first flashed "Press to start recording" on every automatically recorded call (issue #31). See
     * [RecordingNoticePolicy].
     *
     * @return whether the VoIP recorder already has this call, for the start that follows.
     */
    private fun postOpeningNotice(action: String?, currentMeta: RecordingMetadata?): Boolean {
        val isStartRequest = action == ACTION_START_RECORDING || action == ACTION_MANUAL_START
        val voipCall = isStartRequest && isVoipCallInProgress()
        if (isStartRequest) sharesStatusNotice = !voipCall
        val opening = RecordingNoticePolicy.opening(
            isStartRequest = isStartRequest,
            hasSession = hasSession || isCurrentlyRecording,
            hasMetadata = currentMeta != null,
            isVoipCall = voipCall,
        )
        val notification = if (opening == RecordingNoticePolicy.Opening.STAND_DOWN) {
            // The VoIP recorder owns this call; this carrier service is about to stop (see the
            // voipCall branch in onStartCommand). Post the benign placeholder, never the actionable
            // Standby — otherwise its Record button lingers beside the VoIP recording all call (#54).
            notificationHelper.getStandingAsideNotification()
        } else {
            val openingState = currentMeta
                ?.takeIf { opening == RecordingNoticePolicy.Opening.PREPARING }
                ?.let { RecordingServiceState.Starting(it) }
                ?: currentState
            notificationHelper.getNotification(openingState)
        }
        startForegroundWithType(notification)
        return voipCall
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

        // A capture starts: nothing an earlier one measured may judge this call (review N28).
        CapturedCaptureLevels.clear()
        // 1. Create a new session (declared here to allow cleanup if startPipeline fails)
        val activeSession = newEngine()
        daemonLossNotified = false
        // A fresh timeline and a fresh mark buffer for this call. beginCall() also drops anything a
        // previous call left behind after ending badly, so marks can never spill onto the next one.
        recordingClock.start()
        PendingFlags.beginCall()
        switchedToMix = false
        lastRouteObservation = null
        inCallSeenThisCall = false

        try {
            // 2. Try to start the pipeline. Pass our stop flag so the (slow, daemon-cold-start) start
            //    aborts before touching the daemon if the call already ended — preventing a mic that
            //    turns on after the call with nothing to stop it.
            val wanted = runCatching {
                routeDecision("start", engineCanSwitch = true, forced = false, msSinceCaptureStart = 0).route
            }.onFailure { AppLogger.w(WifiCallRoute.TAG, "route check at start failed: ${it.message}") }
                .getOrDefault(WifiCallRoute.Route.CALL_SOURCE)
            val started = activeSession.startPipeline(this, metadata, { stopRequested }, wanted)
            if (started == WifiCallRoute.Route.APP_CALL_MIX) {
                switchedToMix = true
                isAppMixSession = true
            } else if (wanted == WifiCallRoute.Route.APP_CALL_MIX) {
                switchedToMix = true // tried once; no loops
                notificationHelper.showErrorNotification(getString(R.string.wifi_call_switch_failed))
            }
            // 3. Success
            captureStartedAt = android.os.SystemClock.elapsedRealtime()
            currentState = RecordingServiceState.Active(activeSession, false, metadata)
            AppLogger.i(TAG, "Recording pipeline started successfully")
            FreeSpaceMonitor.checkAsync(applicationContext, logLine = true)
            CallEndLog.record(
                applicationContext,
                CallEndLog.capturePath(
                    activeSession.capturePathName(),
                    runCatching { AppPreferences(applicationContext).getPrivilegedMode().name }.getOrDefault("?"),
                    runCatching { AppPreferences(applicationContext).getAudioSource() }.getOrDefault("?"),
                    runCatching { AppPreferences(applicationContext).getAudioCodec() }.getOrDefault("?"),
                )
            )
            serviceScope.launch { startRouteWatch() }
            // Belt-and-suspenders: if a STOP landed in the tiny window after the daemon accepted the
            // recording but before we reached here, tear it down immediately so capture (and the mic)
            // does not linger past the call.
            if (stopRequested) {
                AppLogger.w(TAG, "Stop requested during startup; tearing down the just-started session")
                stopRecordingSessionAndService()
            }
        } catch (e: PipelineInitializationException) {
            if (e is OrphanSessionKeptException) {
                // Not a failure: the recorder is recording this call already, for the CallVault process the phone
                // killed mid-call (Q25). It is stopped and saved under its own name when the call ends (C2).
                AppLogger.i(TAG, "Not starting a second recording: ${e.message}")
            } else {
                AppLogger.e(TAG, e.message ?: "", e.cause ?: e)
                notificationHelper.showErrorNotification(e.userFriendlyMessage)
            }
            // Ensure partial resources are cleaned up
            activeSession.cancel(this)
            currentState = RecordingServiceState.Standby(metadata)
            stopRecordingSessionAndService()
        } catch (e: Throwable) {
            // Anything else (a cancelled start, a bug) drops this engine without its release: its recorder claim must
            // not outlive it, or the recorder reads as ours for good and a real orphan is never stopped (C2 item 8).
            activeSession.abandonClaim()
            throw e
        }
    }

    /** A recording engine with the service's failure callbacks attached. */
    private fun newEngine(): AudioRecordingEngine = AudioRecordingEngine().apply {
        // Surface a mid-call daemon death immediately — the pipeline "started successfully" only
        // proves the dispatch worked, and a daemon that dies right after leaves an empty file.
        onDaemonLostDuringRecording = {
            daemonLossNotified = true
            notificationHelper.showErrorNotification(getString(R.string.recording_error_daemon_died))
        }
        // A live daemon that never started a capture — typically a codec or bit rate the device
        // refuses. Not routed through daemonLossNotified, because the daemon is alive and the two
        // messages say different things.
        onCaptureNeverStarted = {
            notificationHelper.showErrorNotification(getString(R.string.recording_error_capture_never_started))
        }
    }

    // ── Wi-Fi call route (#45) ─────────────────────────────────────────────────────

    /** Decides the route from a live snapshot and logs it when it or what it saw changed. */
    private fun routeDecision(
        where: String,
        engineCanSwitch: Boolean,
        forced: Boolean,
        msSinceCaptureStart: Long,
    ): WifiCallRoute.Decision {
        val (raw, seen) = WifiCallRoute.snapshot(
            this, switchedToMix, forced, appPreferences.isVoipRecordingEnabled(), msSinceCaptureStart
        )
        if (raw.mode == android.media.AudioManager.MODE_IN_CALL) inCallSeenThisCall = true
        val inputs = raw.copy(
            daemonConnected = raw.daemonConnected && engineCanSwitch,
            inCallSeenFirst = inCallSeenThisCall,
        )
        val decision = WifiCallRoute.decide(inputs)
        val observation = WifiCallRoute.Observation(decision, seen)
        if (WifiCallRoute.isWorthLogging(lastRouteObservation, observation)) {
            AppLogger.i(WifiCallRoute.TAG, "route[$where] ${decision.route} (${decision.reason}) $seen")
            if (decision.reason == WifiCallRoute.Reason.NOT_SAMSUNG) {
                AppLogger.i(WifiCallRoute.TAG, "Would have switched to the app-call capture, but this is not a Samsung; decision only")
            }
            if (decision.reason == WifiCallRoute.Reason.APP_CALLS_OFF) {
                AppLogger.i(WifiCallRoute.TAG, "This looks like a Wi-Fi call; with app-call recording on it could be recorded with both sides")
            }
        }
        lastRouteObservation = observation
        return decision
    }

    private fun startRouteWatch() {
        if (isShuttingDown || currentState !is RecordingServiceState.Active) return
        routeWatcher?.stop()
        routeWatcher = WifiCallRouteWatcher(this) {
            try {
                onRouteEvent()
            } catch (t: Throwable) {
                AppLogger.w(WifiCallRoute.TAG, "route check failed: ${t.message}")
            }
        }.also { it.start() }
    }

    /** Main thread. Starts the one-way switch when the call turns out to be a Wi-Fi call. */
    private fun onRouteEvent() {
        val active = currentState as? RecordingServiceState.Active ?: return
        if (stopRequested || isShuttingDown) return
        val sinceStart = android.os.SystemClock.elapsedRealtime() - captureStartedAt
        val decision = routeDecision(
            "change", engineCanSwitch = !active.engine.isLocalFallback, forced = false, msSinceCaptureStart = sinceStart
        )
        if (decision.reason == WifiCallRoute.Reason.WINDOW_PASSED) {
            // Never switches after the window, so stop watching.
            routeWatcher?.stop()
            routeWatcher = null
            return
        }
        if (decision.route != WifiCallRoute.Route.APP_CALL_MIX) return
        synchronized(switchLock) {
            if (switchInProgress || switchedToMix) return
            switchedToMix = true
            switchInProgress = true
        }
        serviceScope.launch(Dispatchers.IO) { switchToAppCallMix(decision.reason) }
    }

    /**
     * Replaces the carrier capture with the app-call capture, mid-call, once.
     *
     * The first capture is fully closed and its file discarded BEFORE the mix and mic open: two voice
     * captures at once silently drop the user's own side. If the mix cannot start, the carrier capture
     * is started again once. Nothing thrown here may escape: this runs mid-call on a scope with no
     * handler, and a crash would lose the call.
     */
    private fun switchToAppCallMix(reason: WifiCallRoute.Reason) {
        try {
            runSwitch(reason)
        } catch (e: Throwable) {
            // Throwable, not Exception: an Error from native handoff code must not crash the app mid-call.
            AppLogger.e(WifiCallRoute.TAG, "switch failed unexpectedly: ${e.message}", e)
            notificationHelper.showErrorNotification(getString(R.string.wifi_call_switch_aborted))
            // An Active state still holding the old engine is fine: release() is idempotent and
            // publishes whatever it kept at the end of the call.
        } finally {
            val stopNow = synchronized(switchLock) {
                switchInProgress = false
                stopDuringSwitch.also { stopDuringSwitch = false }
            }
            // Directly, not through serviceScope: onDestroy may already have cancelled it, and a
            // stop that never runs would leave the new capture (and the mic) on.
            if (stopNow || stopRequested) stopRecordingSessionAndService()
        }
    }

    private fun runSwitch(reason: WifiCallRoute.Reason) {
        val active = currentState as? RecordingServiceState.Active
        if (active == null || stopRequested) {
            AppLogger.i(WifiCallRoute.TAG, "switch skipped: the call is ending")
            return
        }
        // The window bounds what a switch can discard, including when a STOP lands between this
        // check and the release (the call then keeps nothing of those seconds). Re-checked here in
        // case the queue was slow.
        val sinceStart = android.os.SystemClock.elapsedRealtime() - captureStartedAt
        if (sinceStart > WifiCallRoute.SWITCH_WINDOW_MS + SWITCH_QUEUE_GRACE_MS) {
            AppLogger.w(WifiCallRoute.TAG, "switch skipped: ${sinceStart}ms since the capture started, past the window")
            return
        }
        val old = active.engine
        val from = when {
            old.handoffRecording -> "handoff"
            old.daemonRecording -> "daemon"
            else -> "local"
        }
        val discardedMs = recordingClock.audioElapsedMs()
        if (!old.release(discard = true)) {
            // Possibly still live: opening the mix and mic now would make two voice captures. The
            // old engine stays the session untouched; the call-end stop asks it to stop again and
            // finalises one file (the part before the failed stop, or the whole call if it kept going).
            AppLogger.w(WifiCallRoute.TAG, "switch from=$from abandoned: the first capture did not confirm it stopped; it stays the session")
            notificationHelper.showErrorNotification(getString(R.string.wifi_call_switch_aborted))
            return
        }
        // Turns measured by the discarded capture must not label the new file. The clock and the
        // user's marks are left alone: marks keep their place on the call's timeline.
        CapturedSpeakerTurns.clear()
        CapturedCaptureLevels.clear()
        val startedAt = old.currentStartedAt
        var fresh = startReplacement(active.metadata, startedAt, WifiCallRoute.Route.APP_CALL_MIX)
        val mix = fresh?.isAppCallMix == true
        if (fresh == null && !stopRequested) {
            AppLogger.w(WifiCallRoute.TAG, "switch from=$from: restarting the carrier capture once")
            fresh = startReplacement(active.metadata, startedAt, WifiCallRoute.Route.CALL_SOURCE)
        }
        AppLogger.i(WifiCallRoute.TAG, "switch from=$from reason=$reason discarded=${discardedMs}ms startVoip=$mix restarted=${fresh != null}")
        isAppMixSession = mix
        if (fresh == null) {
            if (!stopRequested) notificationHelper.showErrorNotification(getString(R.string.wifi_call_switch_aborted))
            currentState = RecordingServiceState.Standby(active.metadata)
            stopRequested = true
            return
        }
        if (!mix) notificationHelper.showErrorNotification(getString(R.string.wifi_call_switch_failed))
        currentState = RecordingServiceState.Active(fresh, false, active.metadata)
    }

    /** One start attempt for the switch; null on any failure, with its staging file removed. */
    private fun startReplacement(
        metadata: RecordingMetadata,
        startedAt: Date?,
        route: WifiCallRoute.Route,
    ): AudioRecordingEngine? {
        val engine = newEngine()
        return try {
            engine.startPipeline(this, metadata, { stopRequested }, route, startedAt)
            engine
        } catch (e: Throwable) {
            when (e) {
                is CancellationException -> AppLogger.i(WifiCallRoute.TAG, "replacement $route: the call ended (${e.message})")
                is PipelineInitializationException -> AppLogger.e(WifiCallRoute.TAG, "replacement $route failed: ${e.message}", e.cause ?: e)
                else -> AppLogger.e(WifiCallRoute.TAG, "replacement $route failed unexpectedly: ${e.message}", e)
            }
            runCatching { engine.release(discard = true) }
                .onFailure { AppLogger.w(WifiCallRoute.TAG, "cleanup after a failed replacement: ${it.message}") }
            // The engine may not have recorded its mode before failing, so its release can miss a
            // capture the recorder already accepted. Ask directly; both calls are idempotent.
            runCatching { RecorderConnection.service?.stopRecording() }
                .onFailure { AppLogger.w(WifiCallRoute.TAG, "stopRecording after a failed replacement: ${it.message}") }
            if (HandoffReceiver.isLive) {
                runCatching { HandoffReceiver.abort() }
                runCatching { RecorderConnection.service?.stopHandoff() }
            }
            null
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
        // A Wi-Fi-call switch is closing one capture and opening another; it finishes the stop itself.
        val deferred = synchronized(switchLock) {
            if (switchInProgress) stopDuringSwitch = true
            switchInProgress
        }
        if (deferred) {
            AppLogger.i(TAG, "Stop requested during a capture switch; the switch will finish it")
            return
        }
        // First, so no state change from here on re-posts the notification this is about to remove.
        // The finished recording keeps showing as it was until the notification goes.
        isShuttingDown = true
        isAppMixSession = false
        routeWatcher?.stop()
        routeWatcher = null
        // The timeline belongs to the call that just ended; leaving it running would date the next
        // call's marks from this one's start.
        recordingClock.reset()
        val activeSession = (currentState as? RecordingServiceState.Active)?.engine
        if (activeSession == null) {
            AppLogger.d(TAG, "No active session, exiting standby state, removing foreground notification and stopping service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Hands the shared notification back, so "Ready to record calls" replaces the finished call.
            SharedStatusNotice.release(SharedStatusNotice.Owner.CARRIER)
            stopSelf() // Stop the service since the session is over
            return
        }
        AppLogger.i(TAG, "Stopping active recording session, remove foreground notification and stopping service...")

        // Capture metadata before releasing resources, in case we need to query call logs for the final file name if phone number is empty.
        val originalMetadata = activeSession.initializationMetadata
        // Capture mimeType before release (currentCodecEnum is not cleared by release()).
        val mimeType = activeSession.currentCodecEnum.mimeType
        // And the start time, for the same reason: the rename below runs later, on another thread.
        val startedAt = activeSession.currentStartedAt
        val appCallMix = activeSession.isAppCallMix

        // Release all resources held by the recording session, stopping the ADB transport and finalizing the recording file.
        activeSession.release()
        val farHeard = activeSession.farPartyHeard
        // What the carrier capture measured (review N28): published by the handoff encoder, or read from the
        // direct capture's diagnostics by release(). Null on the app-call capture, which has its own check, and
        // on a path that measures nothing; both read as "not measured".
        val levels = CapturedCaptureLevels.take().takeUnless { appCallMix }
        if (appCallMix || switchedToMix) {
            AppLogger.i(WifiCallRoute.TAG, "end appCallMix=$appCallMix farHeard=$farHeard bytes=${activeSession.lastCapturedByteCount}")
        }
        val mix = MixResult(appCallMix, farHeard, levels)

        // Read the URI AFTER release, never before. The destination document does not exist while the
        // call is in progress — the recording is staged to app-private storage and published by
        // release() — so reading it earlier yields null and silently skips everything downstream,
        // including the call-log rename fallback below.
        val uriToRename = activeSession.currentRecordingUri
        // Review N5 / 2026-11-20: a call kept in CallVault's own storage (the folder was not usable) takes the same
        // after-call steps, on its file. For a call saved into the folder this is just its document, as before.
        val finished: FinishedFile? = uriToRename?.let { FinishedFile.Document(it) }
            ?: activeSession.waitingRecording?.let { FinishedFile.Waiting(it) }
        if (activeSession.waitingEmpty) {
            AppLogger.e(TAG, "The waiting recording is empty (0 bytes) — capture never ran. Notifying instead of keeping it.")
            notifyEmptyCapture()
            recordHealth(0L, "the waiting recording", mix.healthFarHeard, null)
        }

        // If the initialization metadata do not contain a phone number, we attempt to query the call log as a fallback.
        // TODO: Remove this fallback logic once we have a more reliable way to get phone number (using a privileged shell and hidden api)
        val isHonorPreset = AppPreferences(applicationContext).getFileNameTemplate() == HONOR_DIALER_TEMPLATE
        if (originalMetadata != null && originalMetadata.rawPhoneNumber.isNullOrBlank() && finished != null) {
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
                val finalUri: FinishedFile
                if (finalNumber.isNotBlank()) {
                    val updatedMeta = originalMetadata.copy(rawPhoneNumber = finalNumber)
                    val stamp = startedAt ?: Date()
                    val nameStamp = if (isHonorPreset) {
                        honorCallStartFromLog(applicationContext, originalMetadata.direction, finalNumber, stamp) ?: stamp
                    } else stamp
                    val newName = RecordingFileNameFormatter.formatFileName(
                        applicationContext, updatedMeta, activeSession.currentCodecEnum,
                        startedAt = nameStamp
                    )
                    // Use DocumentsContract.renameDocument (NOT DocumentFile.renameTo): the latter is a
                    // SingleDocumentFile here and throws UnsupportedOperationException, while
                    // renameDocument is supported by ExternalStorageProvider and returns the new URI.
                    var renamedUri: FinishedFile? = null
                    try {
                        renamedUri = finished.renamedTo(newName)
                        if (renamedUri != null) {
                            AppLogger.d(TAG, "Successfully renamed wrongly detected anonymous recording to: $newName")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to rename file using CallLog fallback", e)
                    }
                    // renameDocument may return a new document URI; fall back to the original on failure.
                    finalUri = renamedUri ?: finished
                } else {
                    AppLogger.d(TAG, "Call log confirmed the call is anonymous, or no actual number was found. Keeping file name as is.")
                    finalUri = finished
                }

                // Route exactly once, after any rename has been applied.
                routeFinished(
                    finalUri, mimeType, activeSession.lastCapturedByteCount, originalMetadata.direction, mix
                )
            }
        } else if (finished != null && originalMetadata != null && startedAt != null &&
            (isHonorPreset || originalMetadata.direction == RecordingDirection.OUTGOING)
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                // A call redirection app (e.g. Google Voice) gave this call a proxy number; the call log has the
                // dialled one. Only needed when NEW_OUTGOING_CALL did not already settle it, see [RedirectedCall].
                // TODO: one post-call step that reads this call's log entry once for the blank-number, redirected-number
                //  and Honor-time cases, instead of three branches.
                val logEntry = if (originalMetadata.direction == RecordingDirection.OUTGOING && !originalMetadata.isDialledNumberChecked) {
                    outgoingCallLogEntry(applicationContext, startedAt)
                } else null
                val dialled = logEntry?.let { RedirectedCall.dialledNumber(it, originalMetadata.rawPhoneNumber, PhoneNumberUtils::compare) }
                    ?.let { PhoneNumberManager.sanitizeOemNumber(it) }
                    ?.also { AppLogger.i(TAG, "Call log has '$it' for this call, the system gave '${originalMetadata.rawPhoneNumber}' (call redirection)") }
                val metadata = if (dialled != null) {
                    RecordingMetadata.enrichMetadata(applicationContext, RecordingMetadata(dialled, originalMetadata.direction))
                } else originalMetadata
                // Honor's phone app links the file only if its name carries the call-log time (#56); see [HonorCallStart].
                // When this call's entry was already read above, its DATE is that time.
                val callStart = if (isHonorPreset) {
                    logEntry?.let { Date(it.date) }
                        ?: honorCallStartFromLog(applicationContext, metadata.direction, metadata.getBestNumber(), startedAt)
                } else null
                val movesTime = callStart != null && callStart.time / 1000 != startedAt.time / 1000
                val finalUri = if (dialled != null || movesTime) {
                    val newName = RecordingFileNameFormatter.formatFileName(
                        applicationContext, metadata, activeSession.currentCodecEnum, startedAt = callStart ?: startedAt
                    )
                    val renamed = runCatching { finished.renamedTo(newName) }
                        .onFailure { AppLogger.e(TAG, "Could not rename the recording to its dialled number or call-log time", it) }
                        .getOrNull()
                    if (renamed != null) AppLogger.d(TAG, "Renamed to the dialled number or call-log time: '$newName'")
                    renamed ?: finished
                } else {
                    finished
                }
                routeFinished(finalUri, mimeType, activeSession.lastCapturedByteCount, originalMetadata.direction, mix)
            }
        } else if (finished != null) {
            // Phone number was already known at recording start — no rename needed.
            // Route the file with its current name immediately (once).
            routeFinished(
                finished, mimeType, activeSession.lastCapturedByteCount, originalMetadata?.direction, mix
            )
        }
        // A capture AudioFlinger invalidated mid-call can leave the microphone indicator on with nothing recording;
        // a few seconds from now this looks, and replaces the daemon if it is safe to. See [MicOpAutoHeal].
        MicOpAutoHeal.scheduleAfterCall(applicationContext, "the carrier recording")
        currentState = RecordingServiceState.Standby(null)
        AppLogger.i(TAG, "The recording session has been stopped and resources have been released. Stopping foreground service. Goodbye >3")
        stopForeground(STOP_FOREGROUND_REMOVE)
            // Hands the shared notification back, so "Ready to record calls" replaces the finished call.
            SharedStatusNotice.release(SharedStatusNotice.Owner.CARRIER)
        stopSelf() // Stop the service since the session is over
    }

    /**
     * The call-log DATE of the call just recorded, for the Honor preset (#56), or null when it cannot be found.
     * See [HonorCallStart] for the matching.
     */
    private suspend fun honorCallStartFromLog(
        context: Context,
        direction: RecordingDirection,
        number: String?,
        startedAt: Date
    ): Date? {
        val callStart = pollCallLog(context, startedAt.time - HonorCallStart.MAX_RING_MS, HONOR_LOG_ATTEMPTS) { rows ->
            HonorCallStart.pick(
                rows.map { HonorCallStart.LogEntry(it.date, it.number, it.direction) },
                startedAt.time, direction, number, PhoneNumberUtils::compare
            )
        }
        if (callStart == null) AppLogger.d(TAG, "No call-log entry matched this recording; keeping its own start time")
        return callStart?.let { Date(it) }
    }

    /** The call-log entry of the outgoing call just recorded, or null when it cannot be found. See [RedirectedCall]. */
    private suspend fun outgoingCallLogEntry(context: Context, startedAt: Date): RedirectedCall.LogEntry? {
        if (!PermissionChecks.hasCallLogPermission(context)) return null
        return pollCallLog(context, startedAt.time - RedirectedCall.MAX_CALL_LEAD_MS, REDIRECT_LOG_ATTEMPTS) { rows ->
            RedirectedCall.entryForCall(
                rows.filter { it.direction == RecordingDirection.OUTGOING }
                    .map { RedirectedCall.LogEntry(it.date, it.lastModified, it.number) },
                startedAt.time
            )
        }
    }

    /** One call-log row, as much of it as the post-call lookups need. */
    private data class CallLogRow(val date: Long, val lastModified: Long, val number: String, val direction: RecordingDirection)

    /**
     * Reads the incoming and outgoing calls since [since] until [pick] finds what it needs, up to [attempts] times
     * [HONOR_LOG_RETRY_MS] apart: the entry of the call that just ended is written only after it ends.
     */
    private suspend fun <T : Any> pollCallLog(context: Context, since: Long, attempts: Int, pick: (List<CallLogRow>) -> T?): T? {
        repeat(attempts) { attempt ->
            val rows = runCatching { readCallLogSince(context, since) }
                .onFailure { AppLogger.w(TAG, "Could not read the call log", it) }
                .getOrDefault(emptyList())
            pick(rows)?.let { return it }
            if (attempt < attempts - 1) delay(HONOR_LOG_RETRY_MS)
        }
        return null
    }

    private fun readCallLogSince(context: Context, since: Long): List<CallLogRow> =
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.LAST_MODIFIED),
            "${CallLog.Calls.DATE} >= ?", arrayOf(since.toString()),
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val direction = when (cursor.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> RecordingDirection.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> RecordingDirection.OUTGOING
                        else -> continue
                    }
                    add(CallLogRow(cursor.getLong(0), cursor.getLong(3), cursor.getString(1).orEmpty(), direction))
                }
            }
        }.orEmpty()

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
     * Writes the BCR-compatible details file beside a finished carrier recording.
     *
     * Everything it reports is read back out of the final file name rather than carried down from
     * the call, so the two capture paths derive it identically and neither can drift from the other.
     */
    private suspend fun writeMetadataSidecar(name: String, lastModified: Long, packageName: String, appCallMix: Boolean) {
        val parsed = RecordingsRepository.parseName(name)
        MetadataSidecar.writeIfEnabled(
            context = applicationContext,
            folderUri = AppPreferences(applicationContext).getRecordingFolderUri(),
            audioName = name,
            timestampUnixMs = parsed.startedAtMillis ?: lastModified,
            packageName = packageName,
            direction = when (parsed.direction) {
                RecordingDirection.INCOMING -> "in"
                RecordingDirection.OUTGOING -> "out"
                else -> null
            },
            phoneNumber = parsed.number,
            contactName = parsed.contactName,
            durationSecsEncoded = null,
            // Carrier capture mixes the two directions onto channels whose order is an OEM detail, so
            // name the near side only when the learned mapping is trusted; null otherwise (issue #50).
            // The app-call capture puts the near side on the left by construction (#45).
            nearChannel = if (appCallMix) "A" else SpeakerTurnsRepository.trustedMap(applicationContext).nearChannelKey(),
            simSlot = simSlotFor(parsed.startedAtMillis ?: lastModified),
        )
    }

    /** The 1-based SIM slot of the call that started at [startedAt], when the opt-in SIM label is on; else null. */
    private fun simSlotFor(startedAt: Long): Int? {
        if (!AppPreferences(applicationContext).isShowSimLabelEnabled()) return null
        val calls = PhoneCallReader.since(applicationContext, startedAt - PhoneCallMatch.TOLERANCE_MS)
        val call = PhoneCallMatch.nearest(calls, startedAt) ?: return null
        return SimLabels.Resolver(applicationContext, getString(R.string.sim_label_word))
            .simFor(call.accountComponent, call.accountId)?.slot
    }

    /**
     * Routes the final (post-rename) recording file to the configured storage destination via [StorageRouter].
     * Resolves the display name from the DocumentFile; if the DocumentFile cannot be resolved the call is a no-op.
     *
     * @param uri      The URI of the finalized recording file.
     * @param mimeType The MIME type of the recording (e.g. "audio/opus" or "audio/mp4a-latm").
     */
    private fun routeFinalRecording(
        uri: Uri,
        mimeType: String,
        knownByteCount: Long? = null,
        direction: RecordingDirection? = null,
        mix: MixResult = MixResult(appCallMix = false, farHeard = null),
    ) {
        val doc = DocumentFile.fromSingleUri(applicationContext, uri) ?: return
        val name = doc.name ?: return
        // Prefer the exact captured count when we have it (staged recordings): a cloud destination like
        // Google Drive reports length 0 right after the write (async upload), which would otherwise make
        // a genuine recording look empty. Fall back to the document length for direct-to-SAF (local) files.
        val sizeBytes = knownByteCount ?: doc.length()
        val lastModified = doc.lastModified()
        // An empty file means capture never actually ran (typically: the daemon died right after
        // startRecording was dispatched — seen when Developer options is off and Wireless debugging
        // teardown kills the daemon). Surface an honest failure instead of cataloging an unplayable
        // entry and copying zero bytes to Drive.
        if (sizeBytes <= 0L) {
            AppLogger.e(TAG, "Recording '$name' is empty (0 bytes) — capture never ran. Deleting it and notifying instead of cataloging.")
            // The mid-call daemon-death warning is the more actionable message and shares the same
            // notification ID — don't overwrite it (and double-vibrate) with the empty-file variant.
            notifyEmptyCapture()
            SafHelper.deleteDocument(doc, "the empty recording '$name'")
            recordHealth(sizeBytes, name, mix.healthFarHeard, mix.healthHeardAnything)
            return
        }
        warnIfCutShortByFullStorage(name)
        // Too short to be worth keeping, if the user asked for that. Before the catalog, the
        // transcription queue and StorageRouter — a recording discarded after any of those has
        // already cost a whisper run or a cloud upload, and would flicker through the Home list on
        // its way out. The duration comes from the container rather than the call log because the
        // call log has nothing to say about an app call, and this check must mean the same thing on
        // both capture paths.
        val minSeconds = AppPreferences(this).getMinDurationSeconds()
        if (minSeconds > 0) {
            val durationMs = CallEndLog.timedProbe(applicationContext, "carrier", name) {
                AudioDecoder.durationMs(applicationContext, uri)
            }
            if (MinDurationPolicy.shouldDiscard(durationMs, minSeconds)) {
                AppLogger.i(
                    TAG,
                    "Discarding '$name': ${durationMs}ms is under the ${minSeconds}s minimum."
                )
                SafHelper.deleteDocument(doc, "the too-short recording '$name'")
                // Deliberately no notification. The whole point of the setting is not being told
                // about a misdial; a user who turned it on has already said they do not want these.
                // It is logged, so a support question about a missing recording is still answerable.
                return
            }
        }

        // Keep or delete after the call: decided ONCE, here, and both halves below follow it — the catalog
        // coroutine (details-file copy, transcription) and the synchronous Drive route after it. Held
        // before either starts, so no sweep can copy it in between. Not held — kept as in Keep mode — when
        // the question could not be shown or the hold could not be stored; see AfterCallDecision.tryHold.
        val isHeld = AfterCallDecision.tryHold(applicationContext, name, uri, mimeType, isVoip = false)
        reportMixOutcome(name, mix)

        // Record this finished recording in CallVault's own catalog (the Home list's source of truth).
        // The file is on the device now, so this is the local copy; the copy/sweep workers later stamp
        // the Drive copy onto this same row by name. Done on a detached IO scope because the service may
        // be torn down right after (the process is given a few seconds to live — same rationale as the
        // CallLog fallback above). Written for every storage target, regardless of where the file lands.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallEndLog.record(applicationContext, CallEndLog.catalogStart(name, sizeBytes))
                val catalogStartedAt = android.os.SystemClock.elapsedRealtime()
                val catalogFailure = RecordingCatalog.recordLocalReporting(applicationContext, name, uri, sizeBytes, lastModified)
                CallEndLog.record(
                    applicationContext,
                    CallEndLog.catalogEnd(name, sizeBytes, android.os.SystemClock.elapsedRealtime() - catalogStartedAt, catalogFailure)
                )
                // Marks are keyed to the FINAL name, for the same reason the sidecar is: an anonymous
                // number gets renamed from the call log after the call, and rows written mid-call would
                // point at a recording that no longer exists.
                FlagRepository.save(applicationContext, name, PendingFlags.drain())
                // Collect the speaker turns BEFORE the details file and before transcription is queued,
                // never after. A transcript labels its segments from these turns, so they have to be on
                // disk by the time whisper starts; and the details file now carries them too (issue #50),
                // so it has to run after this or it would write a file with no turns in it.
                //
                // The daemon gathered them during the call, from the two capture channels before the
                // mono downmix averaged them away. They cannot be recovered from the finished file —
                // it is mono — so this is the only moment they can be collected at all.
                // Not "outgoing" for the app-call capture: its channel layout is fixed, and feeding it to
                // the learned carrier mapping would teach that mapping the wrong thing.
                SpeakerTurnsRepository.collectAfterCall(
                    applicationContext, name, outgoing = direction == RecordingDirection.OUTGOING && !mix.appCallMix
                )
                // The BCR-compatible details file, when the user asked for one. Driven off the FINAL
                // name, after any call-log rename: a sidecar written against the pre-rename name would
                // sit beside nothing and be invisible to every tool that looks for it. After the turns
                // above, so the `callvault` block can carry them.
                writeMetadataSidecar(name, lastModified, "com.android.phone", mix.appCallMix)
                // While a keep-or-delete answer is pending, neither hand-off runs: AfterCallDecision.keep
                // runs both (and the Drive route below) once the user says keep.
                if (!isHeld) {
                    // Route the .json details to Drive too (issue #49). Must be here, after the write above,
                    // because the audio's StorageRouter.route runs synchronously below while this coroutine is
                    // still going — routing the sidecar from there could fire before the file exists. In
                    // IMMEDIATE mode only; DAILY/WEEKLY copy it in the folder sweep. No-op if the user did not
                    // ask for a details file (nothing to find).
                    StorageRouter.routeSidecar(
                        applicationContext, name, MetadataSidecar.sidecarNameFor(name), "application/json"
                    )
                    // After the catalog entry exists, never before: the transcription runner resolves the
                    // audio through the catalog, so queueing first would race it to "no longer in the catalog".
                    TranscriptionScheduler.transcribeAfterCallIfEnabled(applicationContext, name)
                }
                // Draw it now rather than when the user opens it: this phone has just come
                // off a call, and they have not asked to wait for anything yet.
                WaveformDrawWorker.enqueue(applicationContext, name, uri)
            } finally {
                // Last, once the catalog row exists: Keep runs the transcription, which resolves the audio
                // through the catalog and would otherwise race it. In a finally, so a step above that throws
                // can never leave a held recording with no question (its 24-hour check is already scheduled).
                if (isHeld) AfterCallDecision.ask(applicationContext, name)
            }
        }
        recordHealth(sizeBytes, name, mix.healthFarHeard, mix.healthHeardAnything)
        if (!isHeld) StorageRouter.route(applicationContext, uri, name, mimeType)
    }

    /**
     * The "capture never ran" notice. Not over the mid-call daemon-death warning, which is the more actionable message
     * and shares the same notification id (don't overwrite it and double-vibrate).
     */
    private fun notifyEmptyCapture() {
        // A full phone is named only when the facts say so (review R2): low at call start, almost nothing now, no daemon
        // death known, folder on the measured volume. Otherwise the advice below stays, so no real fault is hidden.
        val free = FreeSpaceMonitor.freeBytes(applicationContext)
        val cause = FreeSpacePolicy.emptyCaptureCause(
            lowAtCallStart = FreeSpaceMonitor.lowAtCallStart,
            freeNowBytes = free,
            daemonLossKnown = daemonLossNotified,
            folderOnPhoneStorage = FreeSpaceMonitor.folderOnPhoneStorage(applicationContext),
        )
        CallEndLog.record(
            applicationContext,
            "recording empty: ${if (free < 0) "free space unknown" else "${free / (1024 * 1024)} MB free"}, " +
                "lowAtCallStart=${FreeSpaceMonitor.lowAtCallStart}, daemonLossNotified=$daemonLossNotified, " +
                "${FreeSpaceMonitor.volumeNote(applicationContext)} -> $cause",
        )
        if (cause == FreeSpacePolicy.EmptyCause.STORAGE_FULL) {
            if (!daemonLossNotified) notificationHelper.showErrorNotification(getString(R.string.storage_full_empty_text))
            return
        }
        if (!daemonLossNotified) {
            // The advice has to match the mode. Telling a Shizuku user to check Developer options
            // and Wireless debugging sends them to settings they have never touched and do not need,
            // and away from the one thing that is actually actionable: whether Shizuku is running.
            val emptyFileMessage =
                if (AppPreferences(this).getPrivilegedMode().needsShizuku) {
                    R.string.recording_error_empty_file_shizuku
                } else {
                    R.string.recording_error_empty_file
                }
            notificationHelper.showErrorNotification(getString(emptyFileMessage))
        }
    }

    /**
     * A free-space warning when a call ends with almost no room left. It does NOT claim the call was cut short: a
     * complete long call also ends on a nearly full phone. The file is kept as it is (review R2).
     */
    private fun warnIfCutShortByFullStorage(name: String) {
        val free = FreeSpaceMonitor.freeBytes(applicationContext)
        if (!FreeSpacePolicy.mayHaveBeenCutShort(free)) return
        CallEndLog.record(applicationContext, "call ended with only ${free / (1024 * 1024)} MB free: ${CallEndLog.safeName(name)} free space is very low (warned the user; the file is kept, its length was not checked)")
        if (!daemonLossNotified) notificationHelper.showErrorNotification(getString(R.string.storage_full_cut_short_text))
    }

    /** Both sides or a failure, never a quiet near-only success (#45), for a Wi-Fi call recorded as an app call. */
    private fun reportMixOutcome(name: String, mix: MixResult) {
        reportCarrierLevels(name, mix)
        // The file is kept, as an app call that came out one-sided is, and the health card records it as one-sided.
        if (mix.appCallMix && mix.farHeard == false) {
            AppLogger.e(WifiCallRoute.TAG, "Wi-Fi call '$name': the other side was not heard — only the user's side is on it")
            notificationHelper.showErrorNotification(getString(R.string.wifi_call_one_sided))
        } else if (mix.appCallMix && mix.farHeard == null) {
            // Unknown is not a success. A lost recorder has already said so, more usefully.
            AppLogger.e(WifiCallRoute.TAG, "Wi-Fi call '$name': could not check that the other side was heard")
            if (!daemonLossNotified) notificationHelper.showErrorNotification(getString(R.string.wifi_call_far_unknown))
        }
    }

    /**
     * Review N28: a carrier recording is judged on what the capture measured. One always-on line per call with the
     * levels (so "why was my call flagged / not flagged" can be answered from a report), and a silent call is said so,
     * never stored as a success. The file is kept: the user may still want to look at it.
     */
    private fun reportCarrierLevels(name: String, mix: MixResult) {
        if (mix.appCallMix) return
        val levels = mix.levels
        if (levels == null) {
            CallEndLog.record(applicationContext, "carrier capture levels: not measured for ${CallEndLog.safeName(name)} (a capture path that does not measure, or an older recorder)")
            return
        }
        val verdict = mix.silence
        CallEndLog.record(
            applicationContext,
            "carrier capture levels for ${CallEndLog.safeName(name)}: ${levels.line().removePrefix(CaptureLevels.PREFIX).trim()} " +
                "silence_peak=${CaptureSilence.SILENCE_PEAK} judged_from_ms=${CaptureSilence.MIN_JUDGED_MILLIS} verdict=$verdict",
        )
        if (verdict == CaptureSilence.Verdict.NOTHING_HEARD && !daemonLossNotified) {
            notificationHelper.showErrorNotification(getString(R.string.carrier_call_silent))
        }
    }

    /** A finished carrier recording: its document in the folder, or its file in waiting storage (review N5). */
    private sealed interface FinishedFile {
        data class Document(val uri: Uri) : FinishedFile
        data class Waiting(val file: java.io.File) : FinishedFile
    }

    /** The call-log rename: of the document exactly as before, or of the name a waiting recording will be saved under. */
    private fun FinishedFile.renamedTo(newName: String): FinishedFile? = when (this) {
        is FinishedFile.Document ->
            DocumentsContract.renameDocument(applicationContext.contentResolver, uri, newName)?.let { FinishedFile.Document(it) }
        is FinishedFile.Waiting -> if (WaitingRecordings.rename(file, newName)) this else null
    }

    private fun routeFinished(
        target: FinishedFile,
        mimeType: String,
        knownByteCount: Long? = null,
        direction: RecordingDirection? = null,
        mix: MixResult = MixResult(appCallMix = false, farHeard = null),
    ) = when (target) {
        is FinishedFile.Document -> routeFinalRecording(target.uri, mimeType, knownByteCount, direction, mix)
        is FinishedFile.Waiting -> routeWaitingRecording(target.file, mimeType, knownByteCount, direction, mix)
    }

    /**
     * [routeFinalRecording] for a call kept in waiting storage (review 2026-11-20): what only exists now (marks, speaker
     * turns), the minimum-duration setting, the Wi-Fi-call checks and the health card, here; the details file, the
     * keep-or-delete question, the library entry, transcription and the Drive copy when it is saved into a folder
     * ([com.baba.callvault.system.storage.WaitingRecordingsWorker]).
     */
    private fun routeWaitingRecording(file: java.io.File, mimeType: String, knownByteCount: Long?, direction: RecordingDirection?, mix: MixResult) {
        val name = WaitingNote.read(file)?.note?.fileName ?: file.name
        val sizeBytes = knownByteCount ?: file.length()
        CoroutineScope(Dispatchers.IO).launch {
            val kept = WaitingCallSteps.atCallEnd(
                applicationContext, file, name, "carrier",
                outgoingForTurns = direction == RecordingDirection.OUTGOING && !mix.appCallMix,
            )
            if (!kept) return@launch
            reportMixOutcome(name, mix)
            recordHealth(sizeBytes, name, mix.healthFarHeard, mix.healthHeardAnything)
            WaitingRecordings.ready(
                applicationContext, file, mimeType, "phone call",
                WaitingNote.Extras(isVoip = false, appCallMix = mix.appCallMix, direction = null),
            )
        }
    }

    /**
     * Records what this call proved, for the Home status card. Best-effort: a store that cannot be
     * written leaves the previous state rather than corrupting it. `farPartyHeard` is null for the
     * carrier capture, which cannot observe silence; the app-call capture of a Wi-Fi call can.
     */
    private fun recordHealth(sizeBytes: Long, name: String, farPartyHeard: Boolean?, heardAnything: Boolean?) {
        runCatching {
            val outcome = CallOutcomes.of(sizeBytes, daemonLossNotified, farPartyHeard = farPartyHeard, heardAnything = heardAnything)
            SetupHealthStore(applicationContext)
                .record(outcome, System.currentTimeMillis(), name, SetupFingerprint.of(appPreferences))
        }.onFailure { AppLogger.w(TAG, "Could not record setup health for '$name': ${it.message}") }
    }

    /**
     * Updates the foreground service notification based on the current state (Recording or Standby).
     */
    private fun updateNotification() {
        // Stopping: the notification is on its way out, and posting it again would flash a state the
        // user has no use for — or re-promote a service that has already stopped itself.
        if (isShuttingDown) return
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
        // Claimed before posting, so the keep-alive — on the same thread — never sees the id as free and
        // re-posts "Ready" over it. A notice that does not share gives the id back instead.
        val notificationId = if (sharesStatusNotice) {
            SharedStatusNotice.claim(SharedStatusNotice.Owner.CARRIER, notification)
            SharedStatusNotice.ID
        } else {
            SharedStatusNotice.release(SharedStatusNotice.Owner.CARRIER)
            RecordingNotificationHelper.SERVICE_NOTIFICATION_ID
        }
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse is the best type to use from Android 14+ for our call recording use-cases.
            // See: https://developer.android.com/about/versions/14/changes/fgs-types-required#special-use
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android 11-13 uses the not yet restricted Data Sync type.
            // Starting Android 15, dataSync type is restricted to a total of 6 hours runtime in a specific time period.
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }
}

/** How a finished carrier recording was captured, for the end-of-call checks (#45). */
private data class MixResult(val appCallMix: Boolean, val farHeard: Boolean?, val levels: CaptureLevels? = null) {
    /** Only the app-call capture can tell whether the far side was heard; unknown counts as not. */
    val healthFarHeard: Boolean? get() = if (appCallMix) farHeard ?: false else null

    /** Review N28: what the carrier capture's own loudness check made of the call. */
    val silence: CaptureSilence.Verdict get() = if (appCallMix) CaptureSilence.Verdict.UNKNOWN else CaptureSilence.judge(levels)

    /** False only when the check ran and every channel stayed silent; null when nothing was measured or judged. */
    val healthHeardAnything: Boolean?
        get() = when (silence) {
            CaptureSilence.Verdict.SOUND -> true
            CaptureSilence.Verdict.NOTHING_HEARD -> false
            CaptureSilence.Verdict.UNKNOWN -> null
        }
}
