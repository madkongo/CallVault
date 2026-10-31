/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Service
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioMuxer
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.integrations.scrcpy.ScrcpyClient
import com.baba.callvault.integrations.scrcpy.ScrcpyLauncher
import com.baba.callvault.server.DirectAudioRecorderSession
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.services.recording.handoff.HandoffReceiver
import com.baba.callvault.services.recording.handoff.HandoffSource
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.StagingNote
import com.baba.callvault.integrations.scrcpy.ServerExtractor
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.RecordingFileNameFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Date

/**
 * Manages the audio recording pipeline, including the connection to the ADB transport, reading from the audio pipe,
 * parsing scrcpy-server custom stream format, and writing to the output container via [ScrcpyAudioMuxer].
 *
 * Call [startPipeline] to initialize and start the recording, and [release] to clean up resources when done.
 */
class AudioRecordingEngine {

    companion object {
        private const val TAG = "CV:AudioRecordingEngine"

        /** How often the daemon-mode liveness watch re-checks that the daemon's binder is alive. */
        private const val DAEMON_LIVENESS_POLL_MS = 2000L

        /**
         * How long to give a dispatched start before asking whether it actually began.
         *
         * Long enough that an ordinary start has finished — the daemon does the AudioRecord and
         * encoder setup on a worker after the call returns — and short enough to be inside almost
         * any real call. Too eager would report a slow start as a failure; too patient would miss
         * the answer on short calls, which then read as STOPPED and stay quiet.
         */
        private const val CAPTURE_START_CHECK_MS = 3000L

        /** Capture rate for the resilient-recording (handoff) path — matches DirectAudioRecorderSession. */
        private const val HANDOFF_SAMPLE_RATE = 48000

        /** How long a just-dispatched app-call capture is watched for a failed open. */
        private const val VOIP_START_CONFIRM_MS = 1_000L
        private const val VOIP_START_POLL_MS = 100L
    }

    /**
     * Parses the raw byte stream that arrives from the shell process pipe.
     *
     * Calls the attached callbacks with parsed audio packets and stream metadata.
     */
    var scrcpyClient: ScrcpyClient? = null

    /** Writes scrcpy decoded audio packets into the output container (OPUS/AAC). */
    var scrcpyAudioMuxer: ScrcpyAudioMuxer? = null

    /** Metadata captured during the [startPipeline] and locked. Used for checks in [release] if we need to query call logs for the final file name if phone number is empty. */
    var initializationMetadata: RecordingMetadata? = null
        set(value) {
            if (field == null) {
                field = value
            } else {
                AppLogger.w(TAG, "Attempt to overwrite recording session metadata ignored. THIS SHOULD NOT HAPPEN. Original: $field, New: $value")
            }
        }

    /**
     * Active [ScrcpyLauncher] instance that owns the ADB shell stream and audio socket.
     * Created in [startPipeline] and stopped in [release].
     */
    private var scrcpyLauncher: ScrcpyLauncher? = null

    /**
     * Write-access file descriptor for the output file.
     * This is kept open for the duration of the recording so [ScrcpyAudioMuxer] can write to it,
     * and is closed in [release] after the muxer finalizes the container header.
     */
    var outputPfd: ParcelFileDescriptor? = null

    /**
     * URI of the current recording file (the FINAL destination in the user's SAF folder).
     * Used to delete the file if recording fails to start mid-initialization.
     */
    var currentRecordingUri: Uri? = null

    /**
     * Application context captured in [startPipeline], used by [release] to finalise a staged recording
     * (copy the internal temp into the SAF file) without changing the [release]/[cancel] signatures.
     */
    private var appContext: Context? = null

    /**
     * Non-null only when the chosen SAF provider refused `"rw"` and we recorded into this app-private
     * temp file instead (see [SafHelper.createAudioFile]). [release] copies it into [currentRecordingUri]
     * write-only once the container is finalised, then deletes it. Null on the normal direct-to-SAF path.
     */
    private var stagingFile: File? = null

    /**
     * Where the staged recording will be published when the container is finalised.
     *
     * Held rather than derived because the destination document does not exist yet: nothing is created
     * in the user's folder until there is a complete recording to put there. See
     * [SafHelper.createAudioFile].
     */
    private var pendingFolderUri: Uri? = null
    private var pendingFileName: String? = null
    private var pendingMimeType: String? = null

    /**
     * The exact number of audio bytes captured this session, when known reliably — set only after a
     * STAGED recording is successfully copied to its destination (we measured the internal temp before
     * copying). Null on the direct-to-SAF path (there the destination file's own length is authoritative).
     *
     * The empty-recording guard MUST prefer this over re-reading the destination's length: cloud SAF
     * providers (Google Drive, …) report length 0 immediately after a write (async upload), which would
     * otherwise make a real recording look empty and get deleted.
     */
    var lastCapturedByteCount: Long? = null
        private set

    /**
     * Active codec enum resolved from the user's preference and confirmed by the stream header.
     * Updated once [ScrcpyClient.AudioPacketListener.onMetadataReceived] fires.
     * Defaults to [ScrcpyAudioCodec.OPUS] as a safe initial value before the stream header is read.
     */
    var currentCodecEnum: ScrcpyAudioCodec = ScrcpyAudioCodec.OPUS

    /**
     * When this recording's file was named. A later rename (the call-log number fallback) must reuse it:
     * naming it again with "now" would stamp the call with the time it ENDED, and Honor's phone app (#56)
     * matches a recording to its call by that time.
     */
    var currentStartedAt: Date? = null

    /**
     * Coroutine scope for reading from the audio pipe data returned by the ADB transport.
     * Initialised in [startPipeline] and cancelled in [release].
     */
    var audioPipeReadScope: CoroutineScope? = null

    /**
     * The active pipe reading job.
     * We keep a reference so we can wait to finish reading any late bytes during [release].
     */
    var audioPipeReadJob: Job? = null

    /**
     * Whether this session is recording via the persistent privileged daemon (the default + only
     * intended path) vs the local scrcpy fallback. Set true in [startPipeline] when the daemon accepts
     * the recording; false only when the daemon was unavailable and we fell back to the local pipeline.
     * When true the daemon owns scrcpy + muxing and the local muxer/launcher/scope are never created,
     * so [release] must skip their teardown.
     */
    private var daemonMode: Boolean = false

    /**
     * Whether a daemon-mode recording is currently live. In [daemonMode] there is no local
     * [audioPipeReadJob] to reflect liveness, so callers (e.g. RecordingForegroundService's
     * `isCurrentlyRecording`) must consult this instead. Set true once the daemon's `startRecording`
     * is dispatched, cleared in [release].
     */
    @Volatile
    var daemonRecording: Boolean = false
        private set

    /**
     * Whether this session records via the resilient-recording (audio-handoff, Option B) path: the app
     * holds the live capture + encodes locally, so the recording survives the daemon dying mid-call. Set
     * true once the handoff establishes; like [daemonRecording] there is no local pipe-read job, so
     * `isCurrentlyRecording` must consult this. Mutually exclusive with [daemonMode].
     */
    @Volatile
    var handoffRecording: Boolean = false
        private set

    /** True when [startPipeline] chose the handoff path; drives the [release] teardown branch. */
    private var handoffMode: Boolean = false

    /**
     * True when this session records a Wi-Fi call through the app-call capture (loopback mix + mic)
     * instead of VOICE_CALL (#45). The daemon owns it like any daemon-mode recording.
     */
    var isAppCallMix: Boolean = false
        private set

    /**
     * For an app-call-mix session: whether the far side was heard, read from the daemon at [release].
     * Null when it could not be asked (no daemon) or the session is not a mix.
     */
    var farPartyHeard: Boolean? = null
        private set

    /** True when neither the daemon nor the handoff carries this recording (local scrcpy over ADB). */
    val isLocalFallback: Boolean
        get() = !daemonMode && !handoffMode

    /**
     * Fired at most once per session when the liveness watch finds the daemon's binder dead while a
     * daemon-mode recording is supposed to be live (observed when Developer options is off: the
     * daemon dies within ~200ms of Wireless debugging being turned off, so `startRecording` is
     * dispatched into a corpse and the output file stays empty). Set by the owning service BEFORE
     * [startPipeline] to surface an honest "this call is NOT being recorded" failure.
     */
    var onDaemonLostDuringRecording: (() -> Unit)? = null

    /**
     * Raised once when the daemon was asked to record, is alive, and never started a capture.
     *
     * Distinct from [onDaemonLostDuringRecording] because the cause and the remedy are different: the
     * daemon is fine, the *capture* never began — a codec that would not configure, typically. That
     * failure used to be completely silent (see [CaptureStartCheck]), which is why issue #28's
     * reporter could only say "changing to AAC failed to record the next call" and we could not say
     * why.
     */
    var onCaptureNeverStarted: (() -> Unit)? = null

    /**
     * One-shot, a beat after dispatch: did the capture the daemon accepted actually begin?
     *
     * `startRecording` returns void and does its work afterwards, so a start that throws inside the
     * daemon is invisible at the call site. This asks the one question that settles it. Deliberately
     * quiet unless the answer is unambiguous — see [CaptureStartCheck].
     */
    private val captureStartWatch = Runnable {
        if (!daemonRecording) return@Runnable
        val reachable = runCatching {
            RecorderConnection.service?.asBinder()?.pingBinder() == true
        }.getOrDefault(false)
        val recording = runCatching { RecorderConnection.service?.isRecording() == true }.getOrDefault(false)
        val verdict = CaptureStartCheck.verdict(
            daemonReachable = reachable,
            isRecording = recording,
            stopRequested = !daemonRecording,
        )
        AppLogger.i(TAG, "Capture start check: $verdict (daemon reachable=$reachable, recording=$recording)")
        if (verdict.isReportable) {
            AppLogger.e(
                TAG,
                "The daemon is alive but never started a capture. The most likely cause is the " +
                    "selected codec or bit rate refusing to configure on this device — nothing will " +
                    "be recorded for this call.",
            )
            onCaptureNeverStarted?.invoke()
        }
    }

    private val livenessHandler = Handler(Looper.getMainLooper())
    private val livenessWatch = object : Runnable {
        override fun run() {
            if (!daemonRecording) return
            // pingBinder() probes the daemon process directly, so a dead daemon is detected even if
            // the provider's linkToDeath failed and RecorderConnection.isConnected never cleared.
            val alive = runCatching {
                RecorderConnection.service?.asBinder()?.pingBinder() == true
            }.getOrDefault(false)
            if (!alive) {
                AppLogger.e(TAG, "Recorder daemon binder died while a recording is live — no audio is being captured")
                onDaemonLostDuringRecording?.invoke()
                return // One-shot: the daemon-mode pipeline cannot recover mid-call.
            }
            livenessHandler.postDelayed(this, DAEMON_LIVENESS_POLL_MS)
        }
    }

    /**
     * Whether the recording is currently paused by the user.
     *
     * In [daemonMode] this is a no-op for the pipeline: the daemon writes straight into the output fd,
     * so app-side pause cannot drop packets (acceptable v1 gap — see [setter]). In local mode the audio
     * reader honours this flag to skip writing to the muxer while paused.
     */
    @Volatile
    var isPaused: Boolean = false
        set(value) {
            if (daemonMode && value) {
                // Log once on the transition into a (no-op) paused state in daemon mode.
                AppLogger.w(TAG, "pause not supported in persistent-server mode")
            }
            field = value
        }

    /**
     * Orchestrates the initialization and connection of the entire recording pipeline.
     * @param route     [WifiCallRoute.Route.APP_CALL_MIX] tries the app-call capture first and falls back to
     *                  the usual carrier capture if the daemon refuses it.
     * @param startedAt the time to name the file by; a mid-call switch keeps the first capture's.
     * @return the route actually started.
     * @throws PipelineInitializationException if any step of the initialization fails, with details for user-friendly and technical error reporting.
     */
    fun startPipeline(
        context: Service,
        metadata: RecordingMetadata,
        isCancelled: () -> Boolean = { false },
        route: WifiCallRoute.Route = WifiCallRoute.Route.CALL_SOURCE,
        startedAt: Date? = null,
    ): WifiCallRoute.Route {
        initializationMetadata = metadata
        // The daemon bring-up below can block for tens of seconds while it cold-starts (re-enable WD →
        // mDNS reconnect → relaunch). On this OEM the call frequently ENDS during that window. If we
        // proceed, the daemon's startRecording would fire AFTER the call — turning the mic on with
        // nothing left to stop it (stuck-mic bug). [isCancelled] lets the caller (the service, via its
        // stopRequested flag) signal "the call already ended" so we abort before touching the daemon.
        if (isCancelled()) throw CancellationException("Recording aborted before start (call already ended)")
        val preferences = AppPreferences(context)
        val folderUri = preferences.getRecordingFolderUri()

        if (!SafHelper.isFolderValid(context, folderUri)) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_folder_missing),
                technicalLogMessage = "Cannot start recording: Selected Output folder is missing, invalid, or we do not have permission to write to it"
            )
        }

        val codecEnum = ScrcpyAudioCodec.fromKey(preferences.getAudioCodec())
        val bitRate = preferences.getAudioBitRate().takeIf { it > 0 } ?: codecEnum.defaultBitRate
        val audioSourceEnum = ScrcpyAudioSource.fromKey(preferences.getAudioSource())

        AppLogger.i(TAG, "Starting recording pipeline: source=${audioSourceEnum.cliKey} codec=${codecEnum.cliKey} bitrate=$bitRate")

        val nameStamp = startedAt ?: Date()
        currentStartedAt = nameStamp
        val fileName = RecordingFileNameFormatter.formatFileName(context, metadata, codecEnum, startedAt = nameStamp)

        val safResult = SafHelper.createAudioFile(context, folderUri, fileName, codecEnum.mimeType)
            ?: throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_file_creation),
                technicalLogMessage = "Failed to create audio file in SAF storage"
            )

        AppLogger.d(TAG, "Created SAF recording file: ${safResult.uri}")

        appContext = context.applicationContext
        currentRecordingUri = safResult.uri
        outputPfd = safResult.descriptor
        stagingFile = safResult.stagingFile
        pendingFolderUri = safResult.folderUri
        pendingFileName = safResult.fileName
        pendingMimeType = safResult.mimeType

        // Wi-Fi call (#45): the far side is a voice-comm player, so record it the way an app call is
        // recorded. If the daemon refuses, fall through to the normal carrier capture below.
        if (route == WifiCallRoute.Route.APP_CALL_MIX) {
            if (startVoipMixPipeline(context, codecEnum, bitRate, isCancelled)) {
                daemonMode = true
                isAppCallMix = true
                currentCodecEnum = codecEnum
                return WifiCallRoute.Route.APP_CALL_MIX
            }
            AppLogger.w(WifiCallRoute.TAG, "App-call capture refused; recording this call from the call source instead")
        }

        // Resilient recording (Option B) opt-in: when ENABLED and the source can be captured via a Java
        // AudioRecord (voice-call/mic/voice-communication/…), use the handoff pipeline — the daemon hands
        // its live capture to the app, which reads the ring + encodes, so a recording SURVIVES the daemon
        // dying mid-call. Anything else (pref off, or output/playback sources) falls through to the
        // unchanged daemon path below. When the pref is OFF this branch is skipped entirely, so the
        // default recording path is byte-identical to before.
        // Ruled out entirely in Shizuku mode — see [HandoffPolicy]. A Shizuku-hosted process cannot get
        // an AudioRecord into RECORDING state, and unlike the daemon's own capture the handoff has no
        // fallback: the app would hold a record that never produces a frame and write an empty file
        // while reporting success.
        if (HandoffPolicy.isUsable(
                enabled = preferences.isHandoffPersistEnabled(),
                sourceSupported = HandoffSource.supportsHandoff(audioSourceEnum.cliKey),
                mode = preferences.getPrivilegedMode(),
            ) &&
            startHandoffPipeline(context, audioSourceEnum, codecEnum, bitRate, isCancelled)
        ) {
            handoffMode = true
            currentCodecEnum = codecEnum
            return WifiCallRoute.Route.CALL_SOURCE
        }

        // CallVault ALWAYS records via the persistent privileged daemon — it is the app's core
        // mechanism, not an option. Hand the SAF output fd to the daemon and let IT own scrcpy + muxing
        // over binder (no ADB at record time). Only if the daemon truly can't be brought up do we fall
        // through to the local ADB path below, so a call is never lost.
        if (startDaemonPipeline(context, audioSourceEnum, codecEnum, bitRate, isCancelled)) {
            daemonMode = true
            currentCodecEnum = codecEnum
            return WifiCallRoute.Route.CALL_SOURCE
        }
        AppLogger.w(TAG, "Persistent daemon unavailable; falling back to local ADB recording path")
        if (!AdbShell.ensureConnected(context)) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Neither the recorder daemon nor a direct ADB connection is available"
            )
        }

        scrcpyAudioMuxer = ScrcpyAudioMuxer(outputPfd!!.fileDescriptor, safResult.displayName)

        val launcher = try {
            ScrcpyLauncher.start(context, audioSourceEnum, codecEnum, bitRate)
        } catch (e: Exception) {
            throw PipelineInitializationException(
                userFriendlyMessage = e.localizedMessage ?: context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "ScrcpyLauncher.start failed",
                cause = e,
            )
        }
        scrcpyLauncher = launcher

        currentCodecEnum = codecEnum
        scrcpyAudioMuxer?.initialize(currentCodecEnum)

        scrcpyClient = ScrcpyClient(
            input = launcher.audioInput,
            expectedCodec = codecEnum,
            listener = object : ScrcpyClient.AudioPacketListener {
                /**
                 * Called once after the 4-byte codec FourCC is verified from the stream header.
                 * We re-initialise the muxer with the confirmed codec in case it differs from our initial assumption.
                 */
                override fun onMetadataReceived(codec: ScrcpyAudioCodec) {
                    AppLogger.d(TAG, "Stream metadata confirmed: codec=${codec.cliKey} fourCC=0x${codec.codecFourCC.toString(16)}")
                    currentCodecEnum = codec
                    scrcpyAudioMuxer?.initialize(codec)
                }

                /** Called for every audio frame received from the pipe. */
                override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                    if (isPaused) return // Drop packets while paused, do not write to muxer
                    scrcpyAudioMuxer?.writePacket(packet, currentCodecEnum)
                }

                /** Called when the stream ends normally (EOF) or with an error. */
                override fun onStreamEnd(error: String?) {
                    if (error != null) {
                        AppLogger.w(TAG, "Scrcpy-client reported stopping parsing due to an audio stream error: $error")
                    } else {
                        AppLogger.d(TAG, "Scrcpy-client reported our pipe read stream ended normally (EOF)")
                    }
                }
            }
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        audioPipeReadScope = scope
        audioPipeReadJob = scope.launch(Dispatchers.IO) {
            try {
                scrcpyClient?.start()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Audio reader ended: ${e.message}")
            }
        }
        return WifiCallRoute.Route.CALL_SOURCE
    }

    /**
     * startVoipRecording answers before its worker opens the capture; a failed open clears the
     * recorder's "recording" flag. Watched for a moment so a failure can still fall back. A binder
     * error reads as false here, and the caller then stops the capture first.
     */
    private fun voipCaptureConfirmed(service: com.baba.callvault.server.IRecorderService): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + VOIP_START_CONFIRM_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val recording = runCatching { service.isRecording() }.getOrDefault(false)
            if (!recording) return false
            Thread.sleep(VOIP_START_POLL_MS)
        }
        return runCatching { service.isRecording() }.getOrDefault(false)
    }

    /** Empties the staging file before the carrier capture reuses the fd after a failed mix start. */
    private fun rewindOutput() {
        val fd = outputPfd?.fileDescriptor ?: return
        runCatching {
            android.system.Os.ftruncate(fd, 0)
            android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
        }.onFailure { AppLogger.w(TAG, "Could not rewind the output after a failed app-call start: ${it.message}") }
    }

    /**
     * The app-call capture for a Wi-Fi call: the daemon records the voice-comm loopback mix + mic into
     * [outputPfd], exactly as for an app call. Needs the app-call policy armed before the call began.
     *
     * @return false when the daemon is down or refuses; the caller then uses the carrier capture.
     */
    private fun startVoipMixPipeline(
        context: Service,
        codecEnum: ScrcpyAudioCodec,
        bitRate: Int,
        isCancelled: () -> Boolean
    ): Boolean {
        val connected = RecorderBackend.ensureRunning(context)
        if (isCancelled()) {
            AppLogger.w(TAG, "Call ended before the daemon was ready; aborting app-call capture start")
            runCatching { outputPfd?.close() }
            discardStaging()
            throw CancellationException("Recording aborted before start (call ended during daemon cold-start)")
        }
        val service = RecorderConnection.service
        if (!connected || service == null) {
            AppLogger.w(WifiCallRoute.TAG, "App-call capture: daemon unavailable (connected=$connected)")
            return false
        }
        val call = runCatching { service.startVoipRecording(codecEnum.cliKey, bitRate, outputPfd) }
            .onFailure { AppLogger.w(WifiCallRoute.TAG, "startVoipRecording threw: ${it.message}") }
        val started = call.getOrDefault(false)
        AppLogger.i(WifiCallRoute.TAG, "startVoipRecording -> $started")
        // A plain refusal opened nothing. A binder error is unknown, so it is stopped like a failed open.
        if (!started && call.isSuccess) return false
        if (!started || !voipCaptureConfirmed(service)) {
            AppLogger.w(WifiCallRoute.TAG, "App-call capture did not confirm it started; stopping it before the fallback")
            // Idempotent. Closes anything a half-open start left, so it never runs beside the fallback.
            runCatching { service.stopRecording() }
                .onFailure { AppLogger.w(WifiCallRoute.TAG, "stopRecording before the fallback failed: ${it.message}") }
            rewindOutput()
            return false
        }
        daemonRecording = true
        livenessHandler.postDelayed(livenessWatch, DAEMON_LIVENESS_POLL_MS)
        livenessHandler.postDelayed(captureStartWatch, CAPTURE_START_CHECK_MS)
        return true
    }

    /**
     * Persistent-server pipeline: ensure the privileged daemon is running (which also turns Wireless
     * debugging back OFF once connected), then hand it the already-opened SAF output fd + resolved
     * source/codec/bitRate. The daemon owns scrcpy + muxing into [outputPfd]; the engine creates NO
     * local muxer/launcher/client/scope in this mode.
     *
     * @return true if the daemon accepted the recording; false if the daemon is UNAVAILABLE (caller
     *         falls back to the local ADB path) — this never throws, so the fallback can run.
     */
    private fun startDaemonPipeline(
        context: Service,
        audioSourceEnum: ScrcpyAudioSource,
        codecEnum: ScrcpyAudioCodec,
        bitRate: Int,
        isCancelled: () -> Boolean
    ): Boolean {
        AppLogger.i(TAG, "Ensuring recorder daemon is running")
        val connected = RecorderBackend.ensureRunning(context)
        // ensureServerRunning can block for tens of seconds cold-starting the daemon; the call may have
        // ENDED in the meantime. Abort BEFORE startRecording so we never start capture after the call
        // (the stuck-mic bug). Throw (not return false) so the caller does NOT fall back to the local
        // ADB path — there is nothing to record. Close the SAF fd we opened so it isn't leaked.
        if (isCancelled()) {
            AppLogger.w(TAG, "Call ended before the daemon was ready; aborting start so the mic stays off")
            runCatching { outputPfd?.close() }
            stagingFile?.let { runCatching { it.delete() } }
            stagingFile = null
            throw CancellationException("Recording aborted before start (call ended during daemon cold-start)")
        }
        val service = RecorderConnection.service
        if (!connected || service == null) {
            AppLogger.w(TAG, "Recorder daemon unavailable (connected=$connected, service=${service != null})")
            return false
        }
        return try {
            service.startRecording(audioSourceEnum.cliKey, codecEnum.cliKey, bitRate, outputPfd)
            daemonRecording = true
            // Name the capture path the daemon is expected to take. This line used to assert "scrcpy"
            // unconditionally, which predates the direct AudioRecord path and sends whoever reads a bug
            // report to the wrong component — a VOICE_CALL/AAC recording goes direct, not through scrcpy.
            // The daemon logs which path it actually took, but that never reaches this file: it runs in
            // its own process, where AppLogger was never given a context to write one.
            val expectedPath = if (DirectAudioRecorderSession.supports(audioSourceEnum, codecEnum)) {
                "direct AudioRecord"
            } else {
                "scrcpy"
            }
            AppLogger.i(
                TAG,
                "Daemon startRecording dispatched; it owns capture + muxing from here " +
                    "(expected path: $expectedPath — the daemon falls back to scrcpy if the direct path cannot start)"
            )
            // A successful dispatch only proves the binder was alive at that instant — watch it so a
            // daemon that dies moments later surfaces as a failure instead of a silent empty file.
            livenessHandler.postDelayed(livenessWatch, DAEMON_LIVENESS_POLL_MS)
            // And separately: did a capture actually BEGIN? The liveness watch above only asks
            // whether the daemon is alive, and a daemon whose encoder refused to configure is very
            // much alive while recording nothing at all.
            livenessHandler.postDelayed(captureStartWatch, CAPTURE_START_CHECK_MS)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Daemon startRecording failed; will fall back to local path: ${e.message}", e)
            false
        }
    }

    /**
     * Resilient-recording pipeline (Option B): ensure the daemon is up, register the app-side output
     * target ([outputPfd] + the user's codec), then ask the daemon to create the privileged AudioRecord
     * and hand its live `IAudioRecord` + cblk to the app. The app (via [HandoffReceiver]) reads the ring
     * and encodes into [outputPfd] — so the daemon may then die WITHOUT stopping the recording.
     *
     * The push delivery runs synchronously inside `startHandoff`, so by the time it returns the app has
     * received the handoff; [HandoffReceiver.isLive] confirms capture actually started.
     *
     * @return true if the handoff established (capture is live); false → the caller falls back to the
     *         daemon path so a call is never lost. Never throws except the pre-start cancellation.
     */
    private fun startHandoffPipeline(
        context: Service,
        audioSourceEnum: ScrcpyAudioSource,
        codecEnum: ScrcpyAudioCodec,
        bitRate: Int,
        isCancelled: () -> Boolean
    ): Boolean {
        AppLogger.i(TAG, "Ensuring recorder daemon is running (handoff)")
        val t0 = System.currentTimeMillis()
        val connected = RecorderBackend.ensureRunning(context)
        AppLogger.i(TAG, "Handoff: ensureServerRunning=$connected in ${System.currentTimeMillis() - t0}ms")
        // Same cold-start abort as the daemon path: if the call ended while the daemon was coming up,
        // abort before creating any capture so the mic never turns on after the call (stuck-mic bug).
        if (isCancelled()) {
            AppLogger.w(TAG, "Call ended before the daemon was ready; aborting handoff start")
            runCatching { outputPfd?.close() }
            stagingFile?.let { runCatching { it.delete() } }
            stagingFile = null
            throw CancellationException("Recording aborted before start (call ended during daemon cold-start)")
        }
        val service = RecorderConnection.service
        if (!connected || service == null) {
            AppLogger.w(TAG, "Handoff: recorder daemon unavailable (connected=$connected, service=${service != null})")
            return false
        }
        val outFd = outputPfd?.fileDescriptor
            ?: run { AppLogger.w(TAG, "Handoff: no output fd; falling back"); return false }

        // voice-call is captured STEREO (uplink L / downlink R) then downmixed to mono; mono sources stay
        // mono. The app always outputs a single mono track (matches prod), so downmixToMono is always on
        // (a no-op for already-mono capture).
        val preferredChannels = if (audioSourceEnum == ScrcpyAudioSource.VOICE_CALL) 2 else 1
        HandoffReceiver.begin(
            HandoffReceiver.Target(
                outputFd = outFd,
                mime = codecEnum.mimeType,
                muxerFormat = codecEnum.outputFormat,
                bitRate = bitRate,
                downmixToMono = true,
            ),
            // How the receiver rebuilds a capture the platform tore down mid-call. It asks for exactly
            // what was asked for originally, so the replacement matches the recording already in
            // progress; the daemon releases the dead track before creating the new one.
            requestRearm = {
                runCatching {
                    RecorderConnection.service?.startHandoff(
                        audioSourceEnum.cliKey, HANDOFF_SAMPLE_RATE, preferredChannels
                    ) == true
                }.onFailure { AppLogger.w(TAG, "re-arm request failed: ${it.message}") }
                    .getOrDefault(false)
            },
        )
        AppLogger.i(TAG, "Handoff: calling daemon startHandoff(${audioSourceEnum.cliKey}, $HANDOFF_SAMPLE_RATE, $preferredChannels)")
        val t1 = System.currentTimeMillis()
        val delivered = try {
            service.startHandoff(audioSourceEnum.cliKey, HANDOFF_SAMPLE_RATE, preferredChannels)
                .also { AppLogger.i(TAG, "Handoff: startHandoff returned $it in ${System.currentTimeMillis() - t1}ms") }
        } catch (e: Exception) {
            // The daemon can die AFTER it has already pushed the handoff and the app started capturing,
            // but BEFORE this reply returns (DeadObjectException) — precisely the scenario this feature
            // exists to survive. So the binder reply is NOT authoritative; HandoffReceiver.isLive is.
            AppLogger.w(TAG, "Handoff: startHandoff reply failed (${e.message}); checking isLive")
            false
        }
        // isLive is the single source of truth: if the app-side capture actually started, the recording
        // is live regardless of whether the binder reply made it back. Never abort or fall back in that
        // case — doing so would orphan the running capture AND hand the same fd to a second (daemon) writer.
        if (HandoffReceiver.isLive) {
            handoffRecording = true
            AppLogger.i(TAG, "Handoff established (delivered=$delivered); app now owns capture (survives daemon death)")
            return true
        }
        AppLogger.w(TAG, "Handoff did not establish (delivered=$delivered); releasing + falling back to daemon path")
        HandoffReceiver.abort()
        runCatching { service.stopHandoff() }
        return false
    }

    /**
     * Safely releases all held resources in the correct order.
     * Everything is wrapped in runCatching to ignore any exceptions and continue the cleanup.
     *
     * In [daemonMode] (CallVault Plan 5) the daemon owns scrcpy + muxing, so release only asks the daemon
     * to stop and closes the local fd handle; the local muxer/launcher/client/scope were never created so
     * their teardown is skipped. The metadata/call-log finalize done by [RecordingForegroundService] after
     * release() is unaffected (it reads [initializationMetadata]/[currentRecordingUri]/[currentCodecEnum],
     * none of which release() clears).
     *
     * Local mode (flag off) is unchanged:
     * 1. Stops the ScrcpyLauncher (closes ADB shell and audio streams), which gives scrcpy-server
     *    a grace period to write its final audio bytes before closing the pipe from the sender side.
     * 2. Waits for the local reading coroutine to reach EOF and finish parsing the late bytes.
     * 3. Cancels the active reading coroutine and scrcpy client as a fallback.
     * 4. Closes the muxer and output file descriptor to finalize the container header.
     *
     * @param discard close the capture but publish nothing: the staged file is deleted. Used when a
     *                Wi-Fi call switches capture mid-call (#45). Not [cancel], which publishes first.
     *                If the capture did not stop cleanly, nothing is closed, published or deleted and
     *                the engine stays usable: the caller keeps it as the session, and the call-end
     *                release asks again and finalises one complete file.
     * @return whether the capture is known to have stopped. After a full release, later calls do nothing.
     */
    fun release(discard: Boolean = false): Boolean {
        if (released) return true
        released = true
        AppLogger.i(TAG, "Releasing session resources and recording pipeline...")
        livenessHandler.removeCallbacks(livenessWatch)
        livenessHandler.removeCallbacks(captureStartWatch)

        if (handoffMode) {
            // Resilient-recording teardown: stop() flips the drain flag and BLOCKS until the app-side
            // encoder finalises the container trailer into outputPfd; only then close our fd handle. Then
            // best-effort ask the daemon to release its held track (ignored if the daemon already died —
            // which is exactly the case this path exists to survive).
            val appStopped = runCatching { HandoffReceiver.stop() }
                .onFailure { AppLogger.w(TAG, "Handoff stop error during release: ${it.message}") }
                .isSuccess && !HandoffReceiver.isLive
            var daemonStopped = true
            // Say which of the two happened. `service?.stopHandoff()` on a null binder is a silent
            // no-op that runCatching then reports as success, so "the daemon released its microphone"
            // and "there was nobody to ask" produced identical logs — and the daemon is precisely the
            // process that would still be holding the mic. A user reported exactly that shape on 1.5.8
            // (shell process live with the microphone on after a carrier call) and the logs could not
            // tell the two apart.
            val daemon = RecorderConnection.service
            if (daemon == null) {
                AppLogger.w(TAG, "No daemon binder at release: its handoff AudioRecord was never asked to stop")
            } else {
                daemonStopped = runCatching { daemon.stopHandoff() }
                    .onSuccess { AppLogger.i(TAG, "Asked the daemon to release its handoff capture") }
                    .onFailure { AppLogger.w(TAG, "Daemon stopHandoff failed during release: ${it.message}") }
                    .isSuccess
            }
            if (discard && !(appStopped && daemonStopped)) return keepForCallEnd()
            runCatching { outputPfd?.close() }
            handoffRecording = false
            return finishRelease(discard)
        }

        if (daemonMode) {
            // Ask the daemon to stop + finalise the container, then close our local fd handle. The local
            // muxer/launcher/client/scope do not exist in this mode, so there is nothing else to tear down.
            val daemon = RecorderConnection.service
            var stopped = false
            if (daemon == null) {
                AppLogger.w(TAG, "No daemon binder at release: its recording was never asked to stop")
            } else {
                stopped = runCatching { daemon.stopRecording() }
                    .onFailure { AppLogger.w(TAG, "Daemon stopRecording failed during release: ${it.message}") }
                    .isSuccess
                // stopRecording waits only so long for the teardown; ask whether it is really over.
                stopped = stopped && runCatching { !daemon.isRecording() }.getOrDefault(false)
                // Ask what the capture noticed about itself, and write it to the APP's log — the
                // daemon's own log reaches a report only via logcat, which exists only if debug
                // logging was already on when the call happened (issue #28b). An older daemon has no
                // such method, hence the guard; empty is the normal, healthy answer.
                runCatching { daemon.captureDiagnostics() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { AppLogger.w(TAG, "Capture health for the recording just ended: $it") }
                // Asked after stopRecording, as the app-call path does: the session measured it.
                if (isAppCallMix) {
                    farPartyHeard = runCatching { daemon.voipFarPartyHeard() }.getOrNull()
                }
            }
            if (discard && !stopped) return keepForCallEnd()
            runCatching { outputPfd?.close() }
            daemonRecording = false
            return finishRelease(discard)
        }

        val launcherStopped = runCatching { scrcpyLauncher?.stop() }.isSuccess
        if (discard && !launcherStopped) return keepForCallEnd()
        scrcpyLauncher = null

        runCatching {
            runBlocking {
                withTimeoutOrNull(2000L) {
                    audioPipeReadJob?.join()
                }
            }
        }

        runCatching { scrcpyClient?.stop() }
        runCatching { audioPipeReadScope?.cancel() }
        runCatching { scrcpyAudioMuxer?.close() }
        runCatching { outputPfd?.close() }
        return finishRelease(discard)
    }

    private var released = false

    private fun finishRelease(discard: Boolean): Boolean {
        if (discard) discardStaging() else finalizeStagingIfNeeded()
        return true
    }

    /**
     * A discard whose stop was not confirmed: the capture may still be writing. Nothing is closed or
     * published, and the engine is not marked released, so the call-end release asks the recorder
     * to stop again and finalises the whole call into one file.
     */
    private fun keepForCallEnd(): Boolean {
        released = false
        AppLogger.w(TAG, "Capture did not confirm it stopped; leaving it in place for the call-end release")
        return false
    }

    /** Deletes the staged capture without publishing it. Nothing ever reaches the user's folder. */
    private fun discardStaging() {
        val temp = stagingFile ?: return
        stagingFile = null
        pendingFolderUri = null
        pendingFileName = null
        pendingMimeType = null
        val bytes = temp.length()
        runCatching { StagingNote.fileFor(temp).delete() }
        val deleted = runCatching { temp.delete() }.getOrDefault(false)
        AppLogger.i(TAG, "Discarded the staged capture ($bytes bytes, deleted=$deleted); nothing published")
    }

    /**
     * When the recording was staged to an internal temp file (the SAF provider refused `"rw"`), copy the
     * now-finalised container into the SAF destination write-only, then delete the temp. No-op on the
     * normal direct-to-SAF path. MUST run only AFTER [outputPfd] is closed (so the container is complete).
     *
     * On copy failure the temp is intentionally KEPT (so nothing is lost) and the SAF file is left empty
     * — the caller's existing 0-byte handling then reports an honest failure instead of cataloguing junk.
     */
    private fun finalizeStagingIfNeeded() {
        val temp = stagingFile ?: return
        stagingFile = null
        val ctx = appContext
        val folder = pendingFolderUri
        val name = pendingFileName
        val mime = pendingMimeType
        pendingFolderUri = null
        pendingFileName = null
        pendingMimeType = null

        if (ctx == null || folder == null || name == null || mime == null) {
            AppLogger.e(TAG, "Cannot publish staged recording (destination details missing); temp left at ${temp.path}")
            return
        }
        if (!temp.exists() || temp.length() == 0L) {
            // Nothing is created in the user's folder in this case, which is the point: a capture that
            // produced no audio leaves no misleading file behind.
            AppLogger.w(TAG, "Staged recording is empty — publishing nothing (capture never produced audio)")
            runCatching { temp.delete() }
            return
        }

        val bytes = temp.length()
        val published = SafHelper.publishStagedRecording(ctx, folder, name, mime, temp)
        if (published != null) {
            currentRecordingUri = published
            // Trust this measured count downstream — the destination (e.g. Google Drive) may report
            // length 0 right after the write, which would trip the empty-recording guard.
            lastCapturedByteCount = bytes
            AppLogger.i(TAG, "Published recording → $published ($bytes bytes)")
            runCatching { temp.delete() }
        } else {
            // Kept, not deleted: an unpublished recording on internal storage can still be recovered,
            // and a deleted one cannot.
            AppLogger.e(TAG, "Failed to publish staged recording into $folder; keeping temp at ${temp.path}")
        }
    }

    /**
     * Trigger the normal [release] flow, then followed by an attempt to delete the incomplete recording file if it was created
     * during the pipeline initialization.
     */
    fun cancel(context: Context) {
        release()
        try {
            // Usually there is nothing here: the destination is not created until a recording has been
            // made, so a failed start leaves the user's folder untouched. Kept for the case where
            // release() managed to publish before the cancel arrived.
            currentRecordingUri?.let { uri ->
                DocumentFile.fromSingleUri(context, uri)?.delete()
            }
            currentRecordingUri = null
            AppLogger.d(TAG, "Cleaned up after start failure")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to cleanup after start failure", e)
        }
    }
}

/**
 * Custom exception to carry a user-friendly message for UI display
 * and a technical log message for debugging when the pipeline initialization fails.
 */
class PipelineInitializationException(
    val userFriendlyMessage: String,
    technicalLogMessage: String,
    cause: Throwable? = null
) : Exception(technicalLogMessage, cause)
