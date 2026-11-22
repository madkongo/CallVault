/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.system.updates.UpdateReadyNotice
import com.baba.callvault.services.recording.aftercall.AfterCallDecision
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.R
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.health.CallOutcome
import com.baba.callvault.data.health.CallOutcomes
import com.baba.callvault.data.health.Prerequisite
import com.baba.callvault.data.health.SetupFingerprint
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.health.record
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.CaptureRecoveryLog
import com.baba.callvault.server.DaemonSessionOwnership
import com.baba.callvault.server.DaemonStereo
import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.MicYield
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.VoipCallFacts
import com.baba.callvault.server.VoipStartFailure
import com.baba.callvault.data.recordings.RecordingDirection
import kotlinx.coroutines.isActive
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.LiveStagingFiles
import com.baba.callvault.system.storage.WaitingNote
import com.baba.callvault.system.storage.WaitingRecordings
import com.baba.callvault.system.storage.StagingNote
import java.io.File
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.waveform.WaveformDrawWorker
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.system.storage.StorageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.DeviceInfo
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.storage.CutOffRescueWorker
import com.baba.callvault.system.storage.MinDurationPolicy
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.transcripts.FlagRepository
import com.baba.callvault.data.voip.VoipAppPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns "a VoIP call started/ended" into a recording, writing into the same folder as carrier
 * recordings so both appear together in the app.
 *
 * Naming differs from the carrier path out of necessity: a VoIP call leaves no call-log entry and
 * exposes no phone number, so there is no contact to resolve. Recordings are therefore named by time
 * and marked `voip`, which is honest about what we know rather than guessing at who was on the call.
 *
 * Kept deliberately small and independent of [AudioRecordingEngine]: that engine is built around a
 * carrier call's metadata (number, direction), none of which exists here. The ignore list can still apply,
 * by the caller's name or number from the call notification, when the user opts in — see
 * [VoipIgnoredCallers] (issue #51).
 */
object VoipRecordingCoordinator {

    /** Position in the saved audio, for marks placed from the ongoing notification. */
    private val clock = RecordingClock()

    /** Kept so the notification can be re-posted with a new count or paused state. */
    private var currentAppLabel: String? = null

    /** Whether the user has paused this recording from the notification. */
    private var paused = false

    /** Whether the recording is being held open across a carrier call. */
    var isSuspendedForCarrierCall = false
        private set
    private const val TAG = "CV:VoipRec"

    /** Mirrors `VoipAppIdentity.UID_UNKNOWN`, which lives in the daemon-side package. */
    private const val UID_UNKNOWN = -1

    /** How far into an app call the route and mixer latencies are read: past setup, well before the end. */
    private const val ROUTE_REPORT_DELAY_MS = 5_000L

    /** Dump lines folded into one log line; keeps each log line readable and under the logger's limits. */
    private const val ROUTING_LINES_PER_LOG_LINE = 25

    @Volatile private var recording = false

    /** How long a ring that is not yet answered is given to turn into the answer that started this call. */
    private const val RING_ANSWER_WAIT_MS = VoipDirection.RING_ANSWER_WAIT_MS

    /** When the detector saw this call begin (elapsedRealtime), for reading the ring memory as of then (C20). */
    @Volatile private var callDetectedAt = 0L

    /** The app on this call, once known, so its end can be noted in the ring memory (C20). */
    @Volatile private var currentCallPackage: String? = null

    /** The running recording was started from the call-voice audio, not the mode (BabApps item 108). */
    @Volatile private var startedViaNormalMode = false

    /** True while a VoIP recording is running, so the UI can say so. */
    val isRecording: Boolean get() = recording
    @Volatile private var pending: SafHelper.SafResult? = null
    /** A caller learned after the recording started; goes into the name at publish. See [VoipLateCaller]. */
    @Volatile private var lateCaller: String? = null
    private var lateCallerJob: Job? = null
    /** The app, when it could only be identified after the recording started; goes into the name at publish. */
    @Volatile private var lateAppLabel: String? = null
    private var lateAppJob: Job? = null

    /** "Start when they answer": the poll that holds the encode until the app's call timer runs. */
    private var answerHoldJob: Job? = null
    /** True while the encode is held for the answer; the user's own pause is [paused]. */
    @Volatile private var heldForAnswer = false

    /**
     * The ring listener saw this call's ringing picked up (read by [readDirection] before it consumes the
     * answer), an answer for [holdUntilAnswered] that does not depend on the notification the host reads (C49).
     */
    private val answeredByRing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var codecMime: String = "audio/ogg"

    /**
     * Set when the recording turned out, during the call, not to be wanted: the caller is on the ignore list
     * (#51) or the app is switched off. Its outcome line says which (N8).
     */
    @Volatile private var discardLate: VoipOutcome.Reason? = null

    /** True while an orphan check for a start runs off the main thread; a repeat start signal waits for it. */
    @Volatile private var orphanCheckInFlight = false

    /** Counts call ends, so a start continued after an orphan check knows whether its call already ended. */
    @Volatile private var callEnds = 0

    /** Starts that found the recorder not connected, settled when their call ends (review N18). Keyed by [callEnds]. */
    private val notReadyMiss = VoipNotReadyMiss()

    /** Told when a start that went through an orphan check has finished, so the detector can update its state. */
    @Volatile var onDeferredStartFinished: (() -> Unit)? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Settles a recorder session nobody here owns on a background thread, then continues this start on the main
     * thread (review C2 second pass: never block the main thread on a stop). Every step and its duration goes to
     * the always-on log.
     */
    private fun settleOrphanThenStart(context: Context, manual: Boolean, viaNormalMode: Boolean, detectedAt: Long?) {
        orphanCheckInFlight = true
        val endsAtStart = callEnds
        val app = context.applicationContext
        val t0 = android.os.SystemClock.elapsedRealtime()
        Thread {
            val outcome = runCatching { OrphanRecorderSession.resolve(app, "app call start") }
                .onFailure { AppLogger.e(TAG, "Orphan check at an app-call start failed: ${it.message}", it) }
                .getOrNull()
            val verdict = outcome?.let { OrphanSessionPolicy.forStart(it.action, it.stillRecording) }
                ?: OrphanSessionPolicy.StartVerdict.FAIL_BUSY
            val tookMs = android.os.SystemClock.elapsedRealtime() - t0
            CallEndLog.record(app, "app call start: recorder session check ${outcome?.action} -> $verdict in ${tookMs}ms")
            mainHandler.post {
                orphanCheckInFlight = false
                when {
                    callEnds != endsAtStart -> {
                        AppLogger.i(TAG, "The app call ended while its start waited for the orphan check; not starting")
                        logOutcome(app, VoipOutcome.Reason.ENDED_DURING_START_CHECK)
                    }
                    verdict == OrphanSessionPolicy.StartVerdict.ADOPT -> {
                        CallEndLog.record(app, "app call start: the recorder is already recording this call for the process the phone killed; keeping that recording")
                        AppLogger.i(TAG, "Not starting a second recording: the recorder is already recording this app call for the killed CallVault")
                    }
                    else -> {
                        if (verdict != OrphanSessionPolicy.StartVerdict.START) {
                            CallEndLog.record(app, "app call start: recorder still busy ($verdict); the start will be refused and reported")
                        }
                        // FAIL_BUSY continues too: the recorder refuses it and the existing missed-app-call notice says so.
                        runCatching { onCallStarted(app, manual, viaNormalMode, orphanSettled = true, callDetectedAt = detectedAt) }
                            .onFailure { AppLogger.w(TAG, "VoIP start after the orphan check failed: ${it.message}") }
                    }
                }
                runCatching { onDeferredStartFinished?.invoke() }
            }
        }.apply { isDaemon = true; name = "cv-voip-orphan-check" }.start()
    }

    /**
     * Ends the app-call recording on a background thread (C1: a phone call was answered). The claim is marked as
     * being released at once, on the caller's thread, so the phone call's start waits for this stop instead of
     * failing on a busy recorder.
     */
    fun endInBackground(context: Context) {
        if (!recording) {
            // Not recording here, but a start that found no recorder still needs its end (N18). Only the record is
            // taken here; the check itself (a binder call, a folder listing, the library) runs off this thread, so it
            // can never delay the phone call's own start.
            takeNotReadyAtEnd(context)
            return
        }
        DaemonSessionOwnership.beginRelease(this)
        val app = context.applicationContext
        Thread {
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { onCallEnded(app) }.onFailure { AppLogger.w(TAG, "VoIP stop failed: ${it.message}") }
            AppLogger.i(TAG, "App-call recording ended for the phone call in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            // Whatever happened, the phone call's start must not wait on a claim nobody will give back.
            DaemonSessionOwnership.release(this)
        }.apply { isDaemon = true; name = "cv-voip-end-for-call" }.start()
    }

    /**
     * @param manual the user pressed Record (the "Ask me" prompt). An explicit ask outranks the ignore
     *   list, as it does for phone calls.
     */
    @Synchronized
    fun onCallStarted(
        context: Context,
        manual: Boolean = false,
        viaNormalMode: Boolean = false,
        orphanSettled: Boolean = false,
        callDetectedAt: Long? = null,
    ) {
        if (recording) return
        if (orphanCheckInFlight) return
        // A not-ready record of an earlier call whose end never came must never decide this one (N18).
        val notReadyEnv = NotReadyEnv(context.applicationContext)
        notReadyMiss.forgetOtherCalls(callEnds, notReadyEnv)
        val prefs = AppPreferences(context)
        // The master switch sits above the per-type VoIP setting: paused/stopped records nothing.
        val recordingState = prefs.getRecordingState()
        if (!recordingState.isRecordingAllowed) {
            AppLogger.i(TAG, "Master recording switch is $recordingState — not recording this VoIP call.")
            logOutcome(context, VoipOutcome.Reason.MASTER_SWITCH_OFF)
            notReadyMiss.clear("the master recording switch is off", notReadyEnv)
            return
        }
        if (!prefs.isVoipRecordingEnabled()) {
            notReadyMiss.clear("app-call recording is switched off", notReadyEnv)
            return
        }

        val service = RecorderConnection.service
        if (service == null) {
            // Nothing can be started for THIS call (a late start cannot capture it), but whether it went
            // unrecorded is only known at its end (review N18): a CallVault restarted into a call gets here before its
            // recorder connection is back, while the recorder is still recording the call for the killed process.
            notReadyMiss.onNotReadyStart(callEnds, notReadyEnv)
            AppLogger.w(TAG, "VoIP call detected but the daemon is not connected — not recording; a miss is decided at the call's end")
            logOutcome(context, VoipOutcome.Reason.RECORDER_NOT_CONNECTED)
            CallEndLog.record(context, "app call start: the recorder is not connected; whether the call went unrecorded is decided when it ends")
            return
        }
        // From here this start has a recorder: it records, or says itself why not (a refusal, no folder, a switched-off
        // app, an ignored caller). An earlier not-ready start of the same call no longer stands.
        notReadyMiss.clear("a later start of this call found the recorder connected", notReadyEnv)

        // vivo only: turn its audioserver's verbose logging on BEFORE the call opens, so vivo writes its own
        // record-silencing decision for THIS call (voip_audio_log reads it at the end). Re-armed every call,
        // which is the reboot auto-heal — the tag is volatile, so a post-reboot call sets it again; the reply
        // says whether it had survived. A log tag only; no audio behaviour changes, and nothing runs off vivo.
        if (DeviceInfo.isVivo()) {
            runCatching { service.diagnosticDump("vivo_verbose_arm", null) }
                .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { AppLogger.i(TAG, "vivo verbose logging armed for this call: $it") }
        }

        val codec = runCatching { ScrcpyAudioCodec.fromKey(prefs.getAudioCodec()) }
            .getOrDefault(ScrcpyAudioCodec.OPUS)
        val bitRate = prefs.getAudioBitRate().takeIf { it > 0 } ?: codec.defaultBitRate
        // Best-effort; a missing app or name just drops out of the filename.
        // The app comes from the audio stream we are about to record, so it cannot be the wrong app;
        // the caller is then looked up scoped to THAT package, never across all notifications.
        val lookupStart = android.os.SystemClock.elapsedRealtime()
        val callPackage = resolveCallPackage(context, service, "start")
        val appLabel = callPackage?.let { appLabelFor(context, it) }
        val appLookupDone = android.os.SystemClock.elapsedRealtime()
        val caller = callPackage?.let {
            runCatching { service.voipCallerName(it) }
                .onFailure { AppLogger.d(TAG, "caller lookup failed: ${it.message}") }
                .getOrNull()
        }
        val callerLookupDone = android.os.SystemClock.elapsedRealtime()
        // The user's per-app choice, checked before anything is created OR reported. Not later:
        // creating the file first and deleting it would put a recording of an excluded app on disk,
        // however briefly, leaving a window where a sync tool could take a copy of it — and reporting
        // first would tell the user off for getting exactly what they configured. This used to sit
        // BELOW the folder check, so an excluded app still produced "an app call was not recorded"
        // whenever the folder was unwritable (issue #29).
        if (!VoipAppPolicy.shouldRecord(callPackage, prefs.getVoipExcludedPackages())) {
            // Not reportMissed(): this is not a miss. The user asked for this app to be left alone,
            // and telling them off for getting what they configured is how a warning becomes noise.
            AppLogger.i(TAG, "Not recording this call: $callPackage is switched off for recording.")
            logOutcome(context, VoipOutcome.Reason.APP_SWITCHED_OFF)
            return
        }
        // Issue #51, at the same point and for the same reason as the per-app choice: before any file.
        if (!manual && VoipIgnoredCallers.isIgnored(context, caller)) {
            AppLogger.i(TAG, "Not recording this call: the caller is on the ignore list.")
            logOutcome(context, VoipOutcome.Reason.CALLER_IGNORED)
            return
        }

        val folderUri = prefs.getRecordingFolderUri()
        val folderUsable = SafHelper.isFolderValid(context, folderUri)
        // Review N5: a call whose folder is not usable is kept in CallVault's own storage and saved later. Only when
        // that cannot take it either (its cap or the phone's space is reached) is the call refused, as before.
        if (!folderUsable && !WaitingRecordings.canAccept(context)) {
            // User-owned and permanent until they fix it: every call goes the same way until then.
            reportMissedIfReal(context, R.string.voip_missed_no_folder, Prerequisite.RECORDING_FOLDER)
            AppLogger.e(TAG, "VoIP call detected but the recording folder is missing/unwritable")
            logOutcome(context, VoipOutcome.Reason.NO_FOLDER)
            return
        }

        // Review C2: a session the recorder still runs for a CallVault the phone killed. One whose call is over is
        // stopped (and rescued under its own name) so this call can start; one that may be this very call, picked
        // up again after the restart (Q25), is that call's recording: nothing new starts and nothing is "missed".
        // Settled OFF the main thread (a stop can take seconds), then this start continues from the main thread.
        // The cheap check below is one binder call; the usual call (nothing to settle) never leaves this thread.
        if (!orphanSettled && OrphanRecorderSession.needsSettling()) {
            settleOrphanThenStart(context, manual, viaNormalMode, callDetectedAt)
            return
        }
        if (!orphanSettled && DaemonSessionOwnership.isOwnedHere &&
            runCatching { service.isRecording() }.getOrDefault(false)
        ) {
            CallEndLog.record(context, "app call start: the recorder is busy with this app's own other recording; the start will be refused")
        }

        val fileName = buildFileName(codec, appLabel, caller)

        // A usable folder takes exactly today's path; otherwise the call is kept to be saved later (review N5).
        val saf = WaitingRecordings.chooseOutput(
            folderUsable,
            inFolder = { folderUri?.let { SafHelper.createAudioFile(context, it, fileName, codec.mimeType) } },
            waiting = {
                val why = if (folderUsable) "its file could not be created" else SafHelper.folderProblem(context, folderUri) ?: "not usable"
                WaitingRecordings.create(context, folderUri, fileName, codec.mimeType, why, kind = "app call")
            },
        )
        if (saf == null) {
            reportMissedIfReal(context, R.string.voip_missed_no_folder, Prerequisite.RECORDING_FOLDER)
            AppLogger.e(TAG, "Could not create the VoIP output file")
            logOutcome(context, VoipOutcome.Reason.FILE_NOT_CREATED)
            return
        }

        // Ours from before the start is sent, so no orphan check can take it for a leftover (C2); and the file it
        // writes is remembered across a kill, so a later check knows this session's start exactly.
        DaemonSessionOwnership.claim(this)
        OrphanRecorderSession.noteSessionStarted(context, saf.stagingFile, isAppCall = true)
        val started = runCatching {
            DaemonStereo.request(service, prefs.getRecordStereo()) { AppLogger.w(TAG, it) }
            service.startVoipRecording(codec.cliKey, bitRate, saf.descriptor)
        }.onFailure { AppLogger.e(TAG, "startVoipRecording threw: ${it.message}", it) }.getOrDefault(false)

        if (!started) {
            DaemonSessionOwnership.release(this)
            OrphanRecorderSession.noteSessionEnded(context, saf.stagingFile)
            // Most likely the policy was not armed before the call — nothing can be captured now, so
            // remove the empty file rather than leaving a 0-byte recording in the user's folder.
            reportMissedIfReal(context, R.string.voip_missed_not_ready, null)
            AppLogger.e(TAG, "VoIP recording refused by the daemon; discarding the empty file")
            // Nothing in the user's folder yet: the destination is not created until a recording has actually
            // been made. Only the staging file exists, and it holds nothing.
            discardStarted(context, saf, VoipOutcome.Reason.RECORDER_REFUSED, null, "the refused app call")
            return
        }

        recording = true
        startedViaNormalMode = viaNormalMode
        pending = saf
        // The rescue must never take this file for an abandoned one while the call goes on (C12): a pause stops
        // its writes, and a LINE call (item 108) shows no call mode. Released when the call's end is done with it.
        LiveStagingFiles.register(saf.stagingFile)
        codecMime = codec.mimeType
        // A timeline and a mark buffer for this call, then the controls. Before this an app call
        // could be recorded from start to finish with nothing to stop it and nothing to mark.
        clock.start()
        PendingFlags.beginCall()
        currentAppLabel = appLabel
        paused = false
        isSuspendedForCarrierCall = false
        VoipRecordingNotification.show(context, appLabel)
        // The capture had to open now; what waits for the pickup is the encode. See VoipAnswerHold.
        // Not a normal-mode call (BabApps item 108, review C31 rework): its notice gives no proven answer signal,
        // and a hold on an unproven signal can lose an answered call. Said in the always-on log when it applies.
        val holdApplies = VoipAnswerHold.applies(prefs.isRecordFromAnswerEnabled(), callPackage, viaNormalMode)
        // The same "Recording started" toast and vibration a phone call gets; both follow their settings. Not while
        // held for the answer (N17): the hold says it when it releases, and an unanswered call never says it.
        if (!holdApplies) RecordingNotificationHelper(context).showRecordingStarted()
        AppLogger.i(TAG, "VoIP recording started -> $fileName")
        com.baba.callvault.system.storage.FreeSpaceMonitor.checkAsync(context, logLine = true)
        CallEndLog.record(
            context,
            CallEndLog.capturePath(
                "VoIP (daemon startVoipRecording)",
                runCatching { prefs.getPrivilegedMode().name }.getOrDefault("?"),
                "voip", codec.cliKey,
            )
        )
        // C108: how long the lookups ran before the capture opened (measured, so the change can be judged on real phones).
        CallEndLog.record(context, VoipStartTiming.line(appLookupDone - lookupStart, callerLookupDone - appLookupDone))
        lateCaller = null
        lateCallerJob?.cancel()
        lateAppLabel = null
        lateAppJob?.cancel()
        // The notification is the only place the name exists, and it may not be posted yet at the
        // instant the audio mode flipped. Ask again a few times into the call; the file is only
        // published at the end, so a late answer still names it.
        discardLate = null
        if (caller == null && callPackage != null) lateCallerJob = askAgainForCaller(context, callPackage, manual)
        // Telegram's call audio appears about 2.5 s after the mode flips, past the lookup above, so
        // its recordings went out with no app and no icon. Ask again into the call, as for the caller.
        if (callPackage == null) lateAppJob = askAgainForApp(context, manual)
        routeJob?.cancel()
        routeJob = reportAudioRoute(context, callPackage)
        answerHoldJob?.cancel()
        heldForAnswer = false
        directionJob?.cancel()
        direction.set(null)
        answeredByRing.set(false)
        this.callDetectedAt = callDetectedAt ?: android.os.SystemClock.elapsedRealtime()
        currentCallPackage = callPackage
        directionJob = if (callPackage != null) readDirection(callPackage) else null
        if (holdApplies) {
            answerHoldJob = holdUntilAnswered(context, callPackage!!)
        } else if (viaNormalMode && prefs.isRecordFromAnswerEnabled()) {
            CallEndLog.record(context, "app call answer hold: not held: normal-mode call, no proven answer signal for this app; recording from the start")
        }
    }

    /** The route/latency report of the call in flight — see [reportAudioRoute]. */
    private var routeJob: Job? = null

    /**
     * Writes, a few seconds into the call, what the capture's sync ledger cannot see from inside the
     * host: where the far party is being played (earpiece, speaker, Bluetooth — a Bluetooth route
     * adds output latency the far-party tap sits ahead of), the mixer threads' latencies, and the
     * calling app's version. Issue #41 is read from these lines together with the host's
     * `VoIP sync` lines. Diagnostics only; nothing here may touch the recording.
     */
    private fun reportAudioRoute(context: Context, callPackage: String?): Job = CoroutineScope(Dispatchers.IO).launch {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val appVersion = callPackage?.let { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
            }
            AppLogger.i(TAG, "App-call route at start: ${describeRoute(am)} app=$callPackage/$appVersion")
        }.onFailure { AppLogger.d(TAG, "route report failed: ${it.message}") }
        delay(ROUTE_REPORT_DELAY_MS)
        if (!recording) return@launch
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AppLogger.i(TAG, "App-call route at ${ROUTE_REPORT_DELAY_MS / 1000}s: ${describeRoute(am)}")
            val latency = RecorderConnection.service?.diagnosticDump("audio_latency", null)
                ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" | ")
            AppLogger.i(TAG, "App-call output threads: ${latency ?: "(unavailable)"}")
            // Where the call's audio is going, for a far party that comes out silent — see DiagnosticDumps.
            // Several lines per dump, each capped, so a report carries them whole.
            listOf("voip_policy", "voip_players", "voip_tracks").forEach { key ->
                val dump = RecorderConnection.service?.diagnosticDump(key, null)
                    ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
                if (dump.isEmpty()) AppLogger.i(TAG, "App-call $key: (unavailable)")
                dump.chunked(ROUTING_LINES_PER_LOG_LINE).forEachIndexed { i, part ->
                    AppLogger.i(TAG, "App-call $key [${i + 1}]: ${part.joinToString(" | ")}")
                }
            }
        }.onFailure { AppLogger.d(TAG, "route report failed: ${it.message}") }
    }

    private fun describeRoute(am: AudioManager): String {
        val comm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.let { "${deviceTypeName(it.type)}:${it.productName}" } ?: "none"
        } else "n/a"
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .joinToString(",") { deviceTypeName(it.type) }
        @Suppress("DEPRECATION")
        return "mode=${am.mode} commDevice=$comm speakerphone=${am.isSpeakerphoneOn} sco=${am.isBluetoothScoOn} " +
            "a2dp=${am.isBluetoothA2dpOn} outputs=[$outputs]"
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bt-le"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        else -> "type$type"
    }

    /** The call's direction once the calling app's notification proved it (BabApps item 113); null = unknown. */
    private val direction = java.util.concurrent.atomic.AtomicReference<RecordingDirection?>(null)
    private var directionJob: Job? = null

    /**
     * Reads the calling app's notification ONCE at the start of the call and keeps the first result;
     * see [VoipDirection]. Never depends on "record from answer": the direction is wanted either way.
     * Logs, always on and with no names or numbers, what the app's notification looked like at the
     * first read and again at the first answered read, so real calls show what each app does.
     */
    private fun readDirection(callPackage: String): Job = CoroutineScope(Dispatchers.IO).launch {
        val startedAt = System.currentTimeMillis()
        // What the ring listener heard BEFORE the pickup, asked once and at once: an incoming call's
        // ringing notification is gone by the time the audio mode flips. A recording that starts late into its
        // call (an "Ask me" tap) reads it as of the call's start instead (C20): by now it has forgotten.
        val detectedAt = callDetectedAt
        val late = android.os.SystemClock.elapsedRealtime() - detectedAt > RING_ANSWER_WAIT_MS
        var ring = VoipRingMemory.verdict(
            callPackage,
            if (late) VoipDirection.ringEvidenceAt(detectedAt) else android.os.SystemClock.elapsedRealtime(),
        )
        val ringAtStart = ring
        val listening = VoipRingMemory.listenerConnected
        val absence = VoipDirection.absenceMeansOutgoing(
            alwaysNotified = com.baba.callvault.server.VoipCallerName.isTelegramClient(callPackage),
            listenerConnectedSince = VoipRingMemory.listenerConnectedSince,
            callDetectedAt = detectedAt,
            lastRecordingEndedAt = VoipRingMemory.lastRecordingEndedAt(callPackage),
        )
        // The audio mode can flip a few milliseconds BEFORE the app's notification is rewritten as
        // ongoing (measured: 32 ms, WhatsApp incoming, 2026-11-17), so a ring that is still unanswered
        // gets a moment to turn into an answer. Never longer than 3 s, and never "outgoing" meanwhile.
        var waited = 0L
        while (!late && ring == VoipRingMemory.Verdict.UNRESOLVED_RING && waited < RING_ANSWER_WAIT_MS && isActive && recording) {
            delay(100)
            waited += 100
            ring = VoipRingMemory.verdict(callPackage, android.os.SystemClock.elapsedRealtime())
        }
        if (ring == VoipRingMemory.Verdict.ANSWERED_RING) {
            if (isActive && recording) answeredByRing.set(true)
            VoipRingMemory.consume(callPackage)
        }
        // An app measured to post its incoming notice for every incoming call (Telegram) needs no
        // notification facts: "listener connected, no ring heard" is its outgoing signal, when that absence means
        // anything for THIS recording (C20: watched since before the ring, not a split call's second file).
        VoipDirection.decide(null, ring, listening, absence)?.let {
            if (isActive && recording && direction.compareAndSet(null, it)) refreshStagingNoteName()
        }
        AppLogger.i(
            TAG,
            "App-call direction ring memory ($callPackage): ring=${ringAtStart.name.lowercase()}" +
                (if (ring != ringAtStart) " then ${ring.name.lowercase()} after ${waited}ms" else "") +
                " listenerConnected=$listening absenceMeansOutgoing=$absence late=$late -> ${direction.get()?.token ?: "not decided yet"}"
        )
        while (isActive && recording) {
            val service = RecorderConnection.service ?: return@launch
            val line = try {
                service.voipCallFacts(callPackage)
            } catch (e: Throwable) {
                AppLogger.i(TAG, "App-call direction: the host cannot read call notifications (older daemon?): ${e.javaClass.simpleName}")
                return@launch
            }
            val facts = VoipCallFacts.parse(line)
            val elapsed = System.currentTimeMillis() - startedAt
            if (facts != null) {
                // The first read is the only one that matters, so it is the last (C128): the direction never changes after
                // it, and reading again cost a recorder dump every 500 ms for a minute. An answer is logged by the ring listener.
                val decided = VoipDirection.decide(facts, ring, listening, absence)
                if (isActive && recording && direction.compareAndSet(null, decided)) refreshStagingNoteName()
                AppLogger.i(
                    TAG,
                    "App-call direction at the first read ($callPackage, ${elapsed}ms in): ${facts.describe()} " +
                        "-> ${direction.get()?.token ?: "unknown"}"
                )
                return@launch
            }
            if (!VoipDirection.readAgain(hasFirstRead = false, elapsedMs = elapsed)) {
                AppLogger.i(TAG, "App-call direction: $callPackage has no call-like notification (no call category, CallStyle type or timer flag) after ${elapsed}ms; direction ${direction.get()?.token ?: "unknown"}")
                return@launch
            }
            delay(VoipAnswerHold.POLL_MS)
        }
    }

    /**
     * Review N16: the note beside the staged file holds the name known at the start. Direction, a late app and a late
     * caller are learned afterwards, so each is written into the note too; a rescue after the app was killed then names
     * the file as a normal finish would. Called only after the capture is running (from the direction and late-lookup jobs, never the start path), and never able to
     * affect it: a failure is only logged (the rescue then uses the start-time name, as before).
     */
    private val stagingNoteLock = Any()

    private fun refreshStagingNoteName() {
        val saf = pending ?: return
        val staged = saf.stagingFile ?: return
        val startName = saf.fileName ?: return
        if (saf.waiting) return
        // The name is worked out inside the lock, from everything known by then, so two quick updates cannot
        // land out of order and drop what the earlier one added. One tiny file write.
        runCatching {
            synchronized(stagingNoteLock) {
                // Only for the recording this refresh belongs to: one that already finished must not get its note back.
                pending === saf && StagingNote.updateFileName(
                    staged,
                    VoipLateCaller.finalName(startName, lateAppLabel, lateCaller, VoipDirection.nameToken(direction.get())),
                )
            }
        }
            .onSuccess { if (!it) AppLogger.d(TAG, "Staging note for ${staged.name} not updated (no note, or the recording already ended)") }
            .onFailure { AppLogger.w(TAG, "Could not update the staging note name for ${staged.name}: ${it.message}") }
    }

    /** The answer-hold loop of this call; see [VoipAnswerHoldLoop] for why every step asks [isHoldLive] (N17). */
    private fun holdUntilAnswered(context: Context, callPackage: String): Job = CoroutineScope(Dispatchers.IO).launch {
        val owner = coroutineContext[Job]
        val normalMode = startedViaNormalMode
        val host = object : VoipAnswerHoldLoop.Host {
            override fun isLive(): Boolean = isHoldLive(owner)
            override fun poll(): Int? = runCatching { RecorderConnection.service?.voipCallAnswered(callPackage) }
                .onFailure { AppLogger.w(TAG, "voipCallAnswered failed (treated as no answer to give): ${it.message}") }
                .getOrNull()
            override fun ringAnswered(): Boolean = answeredByRing.get() ||
                VoipRingMemory.verdict(callPackage, android.os.SystemClock.elapsedRealtime()) == VoipRingMemory.Verdict.ANSWERED_RING
            override fun setHeld(hold: Boolean): Boolean = holdEncode(context, hold, owner)
            override fun recordingFromNow(): Boolean = announceRecordingFromNow(context, owner)
            override fun record(line: String) = CallEndLog.record(context, line)
            override fun debug(line: String) = AppLogger.i(TAG, line)
        }
        VoipAnswerHoldLoop(host, normalMode).run()
    }

    /**
     * Whether [owner] is still the answer hold of the call being recorded. Under the lock, so the loop's first look
     * waits for [onCallStarted] to have stored its job, and a look after [onCallEnded] sees the call is over.
     */
    @Synchronized
    private fun isHoldLive(owner: Job?): Boolean = recording && owner != null && answerHoldJob === owner

    /** "Recording started" once the hold lets the call be recorded (N17). Not over the user's own pause. */
    @Synchronized
    private fun announceRecordingFromNow(context: Context, owner: Job?): Boolean {
        if (!isHoldLive(owner)) return false
        if (!paused) RecordingNotificationHelper(context).showRecordingStarted()
        return true
    }

    /**
     * Holds or releases the encode for the answer wait, through the same binder pause the user's
     * Pause button uses. The user's own pause outranks a release: a recording they paused while it
     * was held stays paused when the call is answered. Returns false, changing nothing, when [owner]'s call is over.
     */
    @Synchronized
    private fun holdEncode(context: Context, hold: Boolean, owner: Job?): Boolean {
        if (!isHoldLive(owner)) return false
        heldForAnswer = hold
        if (!hold && paused) return true
        runCatching { RecorderConnection.service?.setVoipPaused(hold) }
            .onFailure { AppLogger.w(TAG, "setVoipPaused for the answer hold failed: ${it.message}") }
        if (hold) clock.pause() else clock.resume()
        VoipRecordingNotification.show(context, currentAppLabel, PendingFlags.count(), paused || hold)
        return true
    }

    /**
     * Identifies the app on the call after the recording started, for an app whose call audio was
     * not up yet when the mode flipped (Telegram). Found, it names the file at publish, labels the
     * notification, gets the same per-app and ignore-list checks the start path makes, and then
     * looks for the caller in that app's notification.
     */
    private fun askAgainForApp(context: Context, manual: Boolean): Job = CoroutineScope(Dispatchers.IO).launch {
        var elapsed = 0L
        for (at in VoipLateCaller.RETRY_DELAYS_MS) {
            delay(at - elapsed)
            elapsed = at
            if (!recording) return@launch
            val service = RecorderConnection.service ?: return@launch
            val pkg = runCatching { resolveCallPackage(context, service, "late") }.getOrNull() ?: continue
            if (!recording) return@launch
            val excluded = AppPreferences(context).getVoipExcludedPackages()
            // A switched-off app costs the whole call, so it is looked up a second time first.
            val second = if (VoipAppPolicy.shouldRecord(pkg, excluded)) null else {
                delay(VoipLateCaller.CONFIRM_DELAY_MS)
                elapsed += VoipLateCaller.CONFIRM_DELAY_MS
                if (!recording) return@launch
                runCatching { resolveCallPackage(context, service, "late") }.getOrNull()
            }
            when (VoipLateCaller.verdict(pkg, second, excluded)) {
                VoipLateCaller.LateAppVerdict.UNSURE -> {
                    AppLogger.i(TAG, "Call app looked like $pkg (switched off), then $second; recording on, asking again later")
                    continue
                }
                VoipLateCaller.LateAppVerdict.DISCARD -> onSwitchedOffAppFoundLate(context, pkg, at)
                VoipLateCaller.LateAppVerdict.RECORD -> onAppFoundLate(context, service, pkg, at, manual)
            }
            return@launch
        }
        AppLogger.i(TAG, "No app found for this call after ${elapsed / 1000} s; the recording stays unnamed")
    }

    /**
     * The user's per-app choice, as at the start, for an app found late and confirmed by a second
     * lookup. Nothing has been published yet — the recording is still in private staging — so ending
     * and discarding it here leaves no trace.
     */
    @Synchronized
    private fun onSwitchedOffAppFoundLate(context: Context, pkg: String, atMs: Long) {
        if (!recording) return
        AppLogger.i(TAG, "Call app found ${atMs / 1000} s into the call and confirmed: $pkg is switched off; stopping and discarding this recording")
        discardLate = VoipOutcome.Reason.APP_SWITCHED_OFF_LATE
        onCallEnded(context)
    }

    @Synchronized
    private fun onAppFoundLate(context: Context, service: IRecorderService, pkg: String, atMs: Long, manual: Boolean) {
        if (!recording) return
        AppLogger.i(TAG, "Call app found ${atMs / 1000} s into the call: $pkg")
        val label = appLabelFor(context, pkg)?.let { sanitiseForFileName(it) }?.takeIf { it.isNotEmpty() }
        lateAppLabel = label
        refreshStagingNoteName()
        currentAppLabel = label
        currentCallPackage = pkg
        if (directionJob == null) directionJob = readDirection(pkg)
        VoipRecordingNotification.show(context, label, PendingFlags.count(), paused || heldForAnswer)
        if (lateCaller != null) return
        val found = runCatching { service.voipCallerName(pkg) }.getOrNull()
        if (found == null) {
            lateCallerJob?.cancel()
            lateCallerJob = askAgainForCaller(context, pkg, manual)
            return
        }
        lateCaller = found
        refreshStagingNoteName()
        if (!manual && VoipIgnoredCallers.isIgnored(context, found)) {
            AppLogger.i(TAG, "The caller is on the ignore list; stopping and discarding this recording")
            discardLate = VoipOutcome.Reason.IGNORED_LATE
            onCallEnded(context)
        }
    }

    private fun askAgainForCaller(context: Context, callPackage: String, manual: Boolean): Job = CoroutineScope(Dispatchers.IO).launch {
        var elapsed = 0L
        for (at in VoipLateCaller.RETRY_DELAYS_MS) {
            delay(at - elapsed)
            elapsed = at
            if (!recording) return@launch
            val service = RecorderConnection.service ?: return@launch
            val found = runCatching { service.voipCallerName(callPackage) }.getOrNull()
            if (found != null) {
                lateCaller = found
                refreshStagingNoteName()
                AppLogger.i(TAG, "Caller name found ${at / 1000} s into the call")
                // The name came too late for the start check (#51). Nothing has been published — the
                // recording is still in private staging — so ending it here and discarding it leaves no
                // trace in the folder, on Drive or in a sync tool.
                if (!manual && VoipIgnoredCallers.isIgnored(context, found)) {
                    AppLogger.i(TAG, "The caller is on the ignore list; stopping and discarding this recording")
                    discardLate = VoipOutcome.Reason.IGNORED_LATE
                    onCallEnded(context)
                }
                return@launch
            }
        }
        AppLogger.i(TAG, "No caller name on the call notification after ${elapsed / 1000} s; the recording stays nameless")
    }

    /**
     * [reportMissed], but only when the phone actually looks like it is on a call — issue #29.
     *
     * **Why a gate is needed at all.** The detector's only signal is `MODE_IN_COMMUNICATION`, which is
     * a routing state: an app that plays a voicemail through the earpiece takes it too. Every such
     * playback reached the code below and told the user a call had gone unrecorded, and wrote an
     * unexplained gap into the health record. The reporter of issue #29 hit that on every voicemail he
     * played, and each replay did it again.
     *
     * **The gate never costs a recording.** It sits in front of the *report*, not in front of the
     * recording, precisely because [CallEvidence] can be wrong: an app that captures with plain `MIC`,
     * or that opens its capture a moment after the mode flips, will not corroborate. The price of that
     * is a warning we do not print — never a call we do not record.
     */
    private fun reportMissedIfReal(context: Context, messageRes: Int, prerequisite: Prerequisite?) {
        if (!CallEvidence.looksLikeACall(context)) {
            AppLogger.i(
                TAG,
                "The audio mode says a call, but nothing on this phone is capturing for one — " +
                    "not reporting a missed call (issue #29)",
            )
            return
        }
        reportMissed(context, messageRes, prerequisite)
    }

    /**
     * Says out loud that a VoIP call went unrecorded.
     *
     * **Why there is no retry here.** The obvious fix — wait a moment for the daemon and start late —
     * cannot work. Capture depends on a dynamic audio policy the daemon registers, and Android fixes
     * a track's routing when the track is *created*: `startVoipRecording` refuses outright with
     * "policy was not armed before the call" for exactly this reason. By the time we notice the call,
     * its audio is already routed. Arming is re-done on every fresh daemon binder
     * (`RecorderConnection.onDaemonReady`), which is what keeps the window small; a call that lands
     * inside it is lost, and no amount of waiting recovers it.
     *
     * So the only honest thing left is to tell the user, because the alternative is what shipped
     * until now: a call recorded by nobody and mentioned by nobody. That is the worst outcome a call
     * recorder has — worse than a duplicate warning, worse than a bad recording — because the user
     * finds out weeks later, if ever.
     *
     * Both a notification (seen now, while they remember the call) and a status-card entry (still
     * there tomorrow). Reporting only, and wrapped: nothing here may throw into the call path.
     *
     * @param prerequisite the user-owned setting to blame, where there is one. Null for a transient
     *   fault of ours, which is recorded as an unexplained gap instead — the two must never blur,
     *   since one excuses the miss and the other is precisely the failure worth chasing.
     */
    private fun reportMissed(context: Context, messageRes: Int, prerequisite: Prerequisite?) {
        // The user turned recording off on purpose (a tester's master switch): a call they chose not to
        // record is not a miss to warn about — neither the notification nor the status-card gap. Matches
        // the carrier sweep's master-switch gate in HomeViewModel.sweepSetupHealth.
        if (!AppPreferences(context).getRecordingState().isRecordingAllowed) {
            AppLogger.i(TAG, "Master recording switch is off — not warning about this deliberately-skipped app call")
            return
        }
        // Reading health can fail; a failure there must not decide to shout. False is the quiet
        // direction, and matches the carrier path's gate in `recordMissedForMissingPrerequisite`.
        val everWorked = runCatching { SetupHealthStore(context).read().lastVerifiedAt > 0L }
            .getOrDefault(false)

        when (VoipMissPolicy.report(everWorked, prerequisite)) {
            MissReport.SILENT -> {
                AppLogger.i(TAG, "An app call went unrecorded, but no call has ever recorded here — staying quiet")
                return
            }

            MissReport.EXCUSED, MissReport.UNEXPLAINED -> Unit
        }

        runCatching {
            RecordingNotificationHelper(context).showErrorNotification(LocalizedContext.of(context).getString(messageRes))
        }.onFailure { AppLogger.w(TAG, "Could not warn about the missed VoIP call: ${it.message}") }

        // Separately guarded from the notification: one is seen now, the other is still there
        // tomorrow, and a failure to write the second must not cost the first.
        runCatching {
            val now = System.currentTimeMillis()
            val store = SetupHealthStore(context)
            // Labelled "App call": there is no number and no contact to name it by, and the card
            // saying which kind of call went missing is most of what makes it actionable.
            val label = context.getString(R.string.voip_missed_label)
            if (prerequisite != null) store.recordMissedWhileNotReady(now, label, prerequisite)
            else store.recordGap(now, label)
        }.onFailure { AppLogger.w(TAG, "Could not record the missed VoIP call: ${it.message}") }
    }

    /**
     * Records a mark at the current position in the app call being recorded.
     *
     * No pause exists on this path, so the clock never stops; the offset is simply time since the
     * recording began. Silently ignored when nothing is recording, which is what a stale
     * notification action looks like.
     */
    fun markMoment(context: Context) {
        val at = clock.audioElapsedMs()
        if (!recording || at == null) {
            AppLogger.w(TAG, "Mark pressed with no VoIP recording running; ignoring.")
            return
        }
        PendingFlags.add(at)
        // Re-post so the button's own label shows the new count — the only confirmation that is
        // actually visible with the shade open.
        VoipRecordingNotification.show(context, currentAppLabel, PendingFlags.count(), paused)
        RecordingNotificationHelper(context).showFlagToast(PendingFlags.count())
        AppLogger.i(TAG, "Marked ${at}ms into the VoIP recording (${PendingFlags.count()} so far).")
    }

    /**
     * Pauses or resumes the recording of the app call in progress.
     *
     * The daemon owns the encode on this path, so the pause has to travel over the binder. A daemon
     * left over from an older APK does not implement `setVoipPaused`, and the call throws; when that
     * happens nothing changes and the notification keeps saying what is actually true, rather than
     * showing "Paused" over a recording that is still running.
     */
    fun setPaused(context: Context, pause: Boolean) {
        if (!recording) {
            AppLogger.w(TAG, "Pause pressed with no VoIP recording running; ignoring.")
            return
        }
        val ok = runCatching { RecorderConnection.service?.setVoipPaused(pause); true }
            .onFailure { AppLogger.w(TAG, "setVoipPaused failed (old daemon?): ${it.message}") }
            .getOrDefault(false)
        if (!ok) return

        paused = pause
        // Resume pressed while the encode is held for the answer: the user has decided to record
        // now, so the hold is over rather than re-applied on the next poll.
        if (!pause && heldForAnswer) releaseHoldForUser(context)
        // The clock follows the encode, or every mark placed after a pause lands late in the file by
        // the length of that pause — the same rule the carrier path follows.
        if (pause) clock.pause() else clock.resume()
        VoipRecordingNotification.show(context, currentAppLabel, PendingFlags.count(), paused)
    }

    /**
     * Ends the answer hold because the user pressed Resume. The hold loop is cancelled, so it will never say
     * "Recording started"; that is said here instead, through the same path, which says nothing once the call is
     * over (review N17 second pass). Under the lock, so it cannot interleave with the loop's own release.
     */
    @Synchronized
    private fun releaseHoldForUser(context: Context) {
        if (!heldForAnswer) return
        val owner = answerHoldJob
        heldForAnswer = false
        if (!announceRecordingFromNow(context, owner)) {
            AppLogger.i(TAG, "Resume released the answer hold, but its call is already over; nothing announced")
        }
        owner?.cancel()
        answerHoldJob = null
        AppLogger.i(TAG, "App-call recording released by the user before the answer")
    }

    /**
     * Holds the recording open while a carrier call takes over, and resumes it into the SAME file.
     *
     * This is not [setPaused]. A pause keeps the microphone; this gives it up, because it has to — a
     * second voice AudioRecord open during a carrier recording silently drops the user's own side of
     * the phone call, which would trade a split app recording for a half-broken phone one.
     *
     * Returns false when the daemon could not do it, which is the signal for the caller to fall back
     * to the old behaviour and simply end the recording. A daemon left over from an older APK does
     * not have this method, and pretending it worked would hold a file open that nothing is writing.
     */
    fun setSuspendedForCarrierCall(context: Context, suspend: Boolean): Boolean {
        if (!recording) return false
        val ok = runCatching { RecorderConnection.service?.setVoipSuspended(suspend); true }
            .onFailure { AppLogger.w(TAG, "setVoipSuspended failed (old daemon?): ${it.message}") }
            .getOrDefault(false)
        if (!ok) return false

        isSuspendedForCarrierCall = suspend
        // The clock follows, so a mark placed after the phone call still lands in the right place in
        // a file that does not contain the held stretch.
        if (suspend) clock.pause() else clock.resume()
        VoipRecordingNotification.show(
            context, currentAppLabel, PendingFlags.count(), paused, heldForCall = suspend
        )
        AppLogger.i(
            TAG,
            if (suspend) "App-call recording held open for a carrier call"
            else "App-call recording resumed into the same file"
        )
        return true
    }

    /**
     * What the audio stack logged during a call whose far party stayed silent, and the vendor's audio switches
     * (the vivo investigation, 2.4.4-rc4).
     *
     * **Off the main thread, always.** `voip_audio_log` greps the device log in the recorder host and takes
     * many seconds; run inline from [onCallEnded] — which the call detector calls on the main thread, holding
     * this object's lock — it froze the app until Android declared it not responding (OP9, 2026-09-29, after
     * an unanswered WhatsApp call). Diagnostics may be late; the app may never wait for them.
     */
    private fun logOneSidedDiagnostics() {
        Thread {
            runCatching {
                RecorderConnection.service?.diagnosticDump("vivo_audio_props", null)
                    ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" | ")
                    ?.let { AppLogger.i(TAG, "App-call vivo_audio_props: $it") }
            }
            runCatching {
                RecorderConnection.service?.diagnosticDump("voip_audio_log", null)
                    ?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
                    .chunked(ROUTING_LINES_PER_LOG_LINE)
                    .forEachIndexed { i, part -> AppLogger.i(TAG, "App-call voip_audio_log [${i + 1}]: ${part.joinToString(" | ")}") }
            }.onFailure { AppLogger.d(TAG, "voip_audio_log failed: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-voip-diagnostics" }.start()
    }

    /** Stops the in-flight VoIP recording, if any. Idempotent. */
    /**
     * @param neverConnected the call ended after a ring-time wait with no call-voice player ever seen
     *   and call mode never back (BabApps item 107): a ring, not a conversation. Discarded quietly.
     */
    @Synchronized
    fun onCallEnded(context: Context, neverConnected: Boolean = false) {
        try {
            stopAtCallEnd(context, neverConnected)
        } finally {
            // C3: last, after the stop and publish (which can take seconds): an update install held back by this call
            // is announced as ready (one notice, no install). Never throws.
            UpdateReadyNotice.onAnyCallEnded(context)
        }
    }

    private fun stopAtCallEnd(context: Context, neverConnected: Boolean) {
        if (!recording) {
            takeNotReadyAtEnd(context)
            callEnds++
            return
        }
        callEnds++
        val liveStaging = pending?.stagingFile
        try {
            endRecording(context, neverConnected)
        } finally {
            // Only once the file is published, discarded, or knowingly left to the rescue (C12).
            LiveStagingFiles.release(liveStaging)
        }
    }

    /**
     * The end of a call whose start found the recorder not connected (review N18). The record is taken under the lock;
     * the check runs on its own thread (it asks the recorder, lists the staging folder and reads the library), so
     * neither the detector's main thread nor a phone call's start waits for it. The detector's after-call orphan check
     * may stop a kept orphan meanwhile: its staged file, or the library entry the rescue makes of it, still counts.
     * Not under the coordinator's lock: a stop in progress holds it for seconds, and a phone call's start must not
     * wait for that. [VoipNotReadyMiss] has its own.
     */
    private fun takeNotReadyAtEnd(context: Context) {
        val app = context.applicationContext
        val env = NotReadyEnv(app)
        val ended = notReadyMiss.takeAtEnd(callEnds, env) ?: return
        Thread {
            runCatching { notReadyMiss.settle(ended, env) }
                .onFailure { AppLogger.e(TAG, "The not-ready check at an app call's end failed: ${it.message}", it) }
        }.apply { isDaemon = true; name = "cv-voip-not-ready-check" }.start()
    }

    /** [VoipNotReadyMiss.Env] on the phone. */
    private class NotReadyEnv(private val context: Context) : VoipNotReadyMiss.Env {
        override fun now(): Long = System.currentTimeMillis()
        override fun looksLikeACall(): Boolean = runCatching { CallEvidence.looksLikeACall(context) }
            .onFailure { AppLogger.w(TAG, "Call evidence unreadable at a not-ready start (counted as a call): ${it.message}") }
            .getOrDefault(true)
        override fun liveCallBeganAt(): Long? = OrphanRecorderSession.liveCallBeganAt(context)
        override fun recorderRecordingUnowned(): Boolean? {
            val service = RecorderConnection.service ?: return null
            return runCatching { service.isRecording() }
                .onFailure { AppLogger.w(TAG, "isRecording failed at the not-ready check: ${it.message}") }
                .getOrNull()?.let { it && !DaemonSessionOwnership.isOwnedHere }
        }
        override fun stagedFiles(): List<File> = runCatching {
            com.baba.callvault.system.storage.SafHelper.stagingDirOrNull(context)?.listFiles().orEmpty().toList()
        }.onFailure { AppLogger.w(TAG, "Could not list the staging folder at the not-ready check: ${it.message}") }
            .getOrDefault(emptyList())
        override fun isLiveStaging(file: File): Boolean = LiveStagingFiles.isLive(file)
        override fun catalogued(): List<Pair<String, Long?>>? = kotlinx.coroutines.runBlocking {
            RecordingCatalog.allOrNull(context)
        }?.map { entry ->
            // The rescue publishes under the name the recording was started with, which carries its start time.
            entry.displayName to (RecordingsRepository.parseName(entry.displayName).startedAtMillis
                ?: entry.lastModified.takeIf { it > 0L })
        }
        override fun record(line: String) = CallEndLog.record(context, line)
        override fun reportMiss() = reportMissed(context, R.string.voip_missed_not_ready, null)
    }

    /** The body of [onCallEnded], under its lock: stop, then publish or discard. */
    private fun endRecording(context: Context, neverConnected: Boolean) {
        recording = false
        // A next recording of the same app moments from now is this call split, not a new outgoing one (C20).
        currentCallPackage?.let { VoipRingMemory.noteRecordingEnded(it, android.os.SystemClock.elapsedRealtime()) }
        currentCallPackage = null
        val saf = pending
        pending = null
        lateCallerJob?.cancel()
        lateCallerJob = null
        lateAppJob?.cancel()
        lateAppJob = null
        answerHoldJob?.cancel()
        answerHoldJob = null
        routeJob?.cancel()
        routeJob = null
        // Ending while still held means nobody picked up: whatever the file holds is the moment
        // before the hold engaged, not a conversation. Discarded rather than published, so an
        // unanswered app call leaves nothing behind — the same as an unanswered phone call.
        // Never a normal-mode call (C31 rework): kept, whatever held it.
        val heldAtEnd = heldForAnswer
        val unanswered = VoipAnswerHold.discardAsUnanswered(heldAtEnd, startedViaNormalMode)
        if (heldAtEnd && !unanswered) {
            CallEndLog.record(context, "app call answer hold: the normal-mode call ended while held; keeping the recording (no proven answer signal)")
        }
        heldForAnswer = false
        val caller = lateCaller
        lateCaller = null
        val lateApp = lateAppLabel
        lateAppLabel = null
        // The name the recording is published and catalogued under: what was learned late filled in.
        directionJob?.cancel()
        directionJob = null
        val callDirection = direction.getAndSet(null)
        val finalName: (String) -> String = { n -> VoipLateCaller.finalName(n, lateApp, caller, VoipDirection.nameToken(callDirection)) }
        // Straight away, not after the file work below: the controls describe a recording that has
        // already stopped, and a Stop button that lingers invites a second press at a moment when
        // the next call may already be starting.
        VoipRecordingNotification.dismiss(context)
        val recordedMs = clock.audioElapsedMs()
        clock.reset()
        currentAppLabel = null
        paused = false
        isSuspendedForCarrierCall = false
        val recorder = RecorderConnection.service
        runCatching { recorder?.stopRecording() }
            .onFailure { AppLogger.w(TAG, "stopRecording failed: ${it.message}") }
        // Gate review item 3: the session is only "ended" once the recorder says it is not recording. A dead or
        // unreachable recorder is not recording anything.
        val stillRecording = recorder?.let { r -> runCatching { r.isRecording() }.getOrNull() } ?: false
        DaemonSessionOwnership.release(this)
        // The recorder's one answer about this call, read once and before any early return: the slot holding it is
        // overwritten at the next start. Review N21b: how the recorder had to build or start this call's sink and mic
        // when the normal way failed (vivo's constructor crash, the rebuild, a shell Application) goes to the
        // always-on log for every app call, recorded, failed or discarded. Nothing when every record was built
        // normally, or from an older recorder.
        val diagnostics = runCatching { recorder?.captureDiagnostics() }
            .onFailure { AppLogger.w(TAG, "Could not read the app-call capture health: ${it.message}") }
            .getOrNull()
        CaptureRecoveryLog.callEndLine(diagnostics)?.let { CallEndLog.record(context, it) }
        val captureHealth = CaptureRecoveryLog.split(diagnostics).first
        if (stillRecording) {
            // Not stopped. Its file stays a KNOWN session (the key is kept): a phone call starting now finds an orphan
            // older than its own beginning, stops it and records, instead of adopting it. Nothing is published from a
            // file still being written; the cut-off rescue saves it under its own name once it is stopped.
            CallEndLog.record(context, VoipOutcome.line(VoipOutcome.Reason.NOT_STOPPED, recordedMs, saf?.stagingFile?.length()))
            AppLogger.w(TAG, "App-call recording did not confirm it stopped; not publishing a file still being written")
            runCatching { saf?.descriptor?.close() }
            CutOffRescueWorker.schedule(context)
            return
        }
        OrphanRecorderSession.noteSessionEnded(context, saf?.stagingFile)
        discardLate?.let { reason ->
            // Before any warning or diagnostic: a call the user asked us to skip is not a one-sided or
            // failed recording, and nothing about it should be reported or kept.
            discardLate = null
            discardStarted(context, saf, reason, recordedMs, "the ignored caller's app call")
            return
        }
        if (neverConnected) {
            AppLogger.i(TAG, "The app call never connected (call mode did not return, no call-voice player seen; ${recordedMs} ms); discarding the ring, no warning")
            discardStarted(context, saf, VoipOutcome.Reason.NEVER_CONNECTED, recordedMs, "the app call that never connected")
            return
        }
        // The capture's own account of how its two sides lined up (read above), written to the APP's log — the
        // same route the carrier path uses — so a report carries it even when the host's ring was
        // not on for the whole call. Issue #41 is diagnosed from this line. Empty from an older host.
        captureHealth
            ?.takeIf { it.isNotBlank() }
            ?.let {
                AppLogger.i(TAG, "App-call capture health: $it")
                // A normal-mode call is new and unproven: its capture health, with the first-second peaks of
                // both sides, also goes to the always-on file.
                if (startedViaNormalMode) CallEndLog.record(context, "normal-mode app call capture health: $it")
            }
        startedViaNormalMode = false
        // Review N30: the call app's own capture was found silenced right after our mic re-take. Always-on, with the
        // evidence (which client, when, how long the check took, and whether we yielded). Only the default-off yield
        // also tells the user, in the specific words below.
        val micContention = MicYield.evidenceIn(captureHealth)
        if (micContention != null) {
            CallEndLog.record(context, "app call mic contention: $micContention")
            if (MicYield.wasYielded(micContention)) {
                runCatching {
                    RecordingNotificationHelper(context)
                        .showErrorNotification(LocalizedContext.of(context).getString(R.string.voip_mic_yielded_warning))
                }.onFailure { AppLogger.w(TAG, "Could not tell the user the recording stopped to keep them audible: ${it.message}") }
            }
        }

        // A recording where the far party was never audible is one-sided. Say so now rather than let it
        // be discovered weeks later — the app may have opted out of capture, or this OEM build may not
        // attach calls to our mix; from here the two are indistinguishable.
        val farHeard = runCatching { RecorderConnection.service?.voipFarPartyHeard() ?: true }
            .getOrDefault(true)   // no service, or an error: assume fine; never cry wolf
        // A capture that never started wrote nothing, and "the far party was not heard" is then trivially
        // true — which used to blame the other app ("it blocks capture") for our own failure to open the
        // microphone (vivo, 2026-09-28). Nothing recorded is reported as that, below; not as one-sided.
        val wroteAnything = saf?.stagingFile?.let { it.exists() && it.length() > 0L } ?: true
        when (VoipAnswerHold.endDiscardReason(unanswered, wroteAnything, recordedMs, farHeard)) {
            // Ending while still held means nobody picked up (N17: asked before the short-stub check, which a held
            // call's stopped clock always matches, and before any one-sided warning about a call nobody answered).
            VoipOutcome.Reason.UNANSWERED -> {
                AppLogger.i(TAG, "App call ended before it was answered; discarding the held recording")
                discardStarted(context, saf, VoipOutcome.Reason.UNANSWERED, recordedMs, "the unanswered app call")
                return
            }
            // #68: WhatsApp was back to MODE_NORMAL before the recording opened, and the 0.7 s of mic
            // that followed was reported as the other app blocking capture.
            VoipOutcome.Reason.ENDED_BEFORE_CAPTURE -> {
                AppLogger.i(TAG, "The app call ended before the recording got going (${recordedMs} ms, far side never heard); discarding it, no warning")
                discardStarted(context, saf, VoipOutcome.Reason.ENDED_BEFORE_CAPTURE, recordedMs, "the app call that ended before recording")
                return
            }
            else -> Unit
        }
        if (!wroteAnything) {
            AppLogger.w(TAG, "VoIP capture never started — nothing was recorded (the recorder's reason is logged above)")
            // Review N21: a vivo phone lost 23 app calls this way with no word to the user and no reason in the
            // always-on log. The recorder's reason, when it sent one, goes there; and only a reason on a call long
            // enough to be one is told to the user, as for any miss.
            val reason = VoipStartFailure.reasonOf(captureHealth)
            CallEndLog.record(
                context,
                "app call capture did not start in the recorder: " +
                    (reason ?: "no reason from the recorder (an older recorder, a call that ended first, or a recorder that died)"),
            )
            if (VoipMissPolicy.tellStartFailure(reason, recordedMs)) {
                reportMissed(context, R.string.voip_missed_capture_failed, null)
            } else {
                AppLogger.i(TAG, "Not telling the user: ${if (reason == null) "the recorder gave no reason" else "the call was under ${VoipStubPolicy.TOO_SHORT_TO_JUDGE_MS} ms"}")
            }
        } else if (!farHeard) {
            logOneSidedDiagnostics()
            AppLogger.w(TAG, "VoIP recording captured only your side — the other app blocks capture")
            // On vivo the far side is silenced by the phone until the user turns on vivo's own "In-app call
            // recording" (Recorder app), so point vivo users to that toggle instead of blaming the app —
            // unless this is overseas firmware, which has no such toggle (see DeviceInfo.vivoInAppRecording).
            val warning = when {
                !DeviceInfo.isVivo() -> R.string.voip_one_sided_warning
                DeviceInfo.isVivoWithoutInAppRecording() -> R.string.voip_one_sided_warning_vivo_overseas
                else -> R.string.voip_one_sided_warning_vivo
            }
            // After a yield (N30) the user was already told the specific reason; two warnings would contradict.
            if (micContention?.let(MicYield::wasYielded) != true) runCatching {
                RecordingNotificationHelper(context)
                    .showErrorNotification(LocalizedContext.of(context).getString(warning))
            }.onFailure { AppLogger.w(TAG, "Could not warn about the one-sided recording: ${it.message}") }
        }

        // Publish now, and only now. The recording was written to app-private storage for the whole
        // call, so nothing has been visible in the user's folder until this moment — which is what
        // stops a sync tool uploading a growing or an empty file. See SafHelper.createAudioFile.
        val published: Uri? = saf?.let { result ->
            val staging = result.stagingFile
            val folder = result.folderUri
            val outName = result.fileName?.let(finalName)
            val mime = result.mimeType
            if (staging == null || (folder == null && !result.waiting) || outName == null || mime == null) {
                AppLogger.e(TAG, "VoIP recording cannot be published: staging details are missing")
                logOutcome(context, VoipOutcome.Reason.STAGING_MISSING, recordedMs, staging?.takeIf { it.exists() }?.length())
                return@let null
            }
            if (!staging.exists() || staging.length() == 0L) {
                AppLogger.w(TAG, "VoIP capture produced no audio; nothing to publish")
                logOutcome(context, VoipOutcome.Reason.NO_AUDIO, recordedMs, staging.takeIf { it.exists() }?.length())
                deleteStaged(staging, "the empty app-call capture")
                return@let null
            }
            if (result.waiting) {
                // The folder was not usable when the call started (review N5): kept under its final name, with the
                // after-call steps of a saved call, and saved into a folder once one is usable.
                if (WaitingRecordings.end(context, staging, outName, mime, kind = "app call")) {
                    keepWaiting(context, staging, outName, mime, recordedMs, farHeard, callDirection)
                } else {
                    logOutcome(context, VoipOutcome.Reason.NO_AUDIO, recordedMs, 0L)
                }
                return@let null
            }
            if (folder == null) {
                // Cannot happen: a call not kept for want of a folder always has one (checked above). Logged if it does.
                AppLogger.e(TAG, "VoIP recording has no folder but was not kept to save later; keeping it in staging")
                logOutcome(context, VoipOutcome.Reason.STAGING_MISSING, recordedMs, staging.length())
                return@let null
            }
            val bytes = staging.length()
            val uri = SafHelper.publishStagedRecording(context, folder, outName, mime, staging)
            if (uri != null) {
                // Its outcome line follows the minimum-duration check below: one final line per call (N8 rework).
                AppLogger.i(TAG, "Published VoIP recording ($bytes bytes) -> $uri")
                deleteStaged(staging, "the published app-call recording")
            } else {
                // Kept, not deleted: an unpublished recording on internal storage is recoverable and a
                // deleted one is not.
                // The folder was lost during the call (review 2026-11-20): moved into waiting storage, to be saved into
                // whichever folder is usable later. If the move fails it stays in staging for the rescue, as before.
                val adopted = WaitingRecordings.adopt(context, staging, outName, mime, kind = "app call", finished = false)
                if (adopted != null) {
                    AppLogger.e(TAG, "Could not publish the VoIP recording; moved into waiting storage to save later")
                    keepWaiting(context, adopted, outName, mime, recordedMs, farHeard, callDirection)
                } else {
                    AppLogger.e(TAG, "Could not publish the VoIP recording; it stays at ${staging.path}")
                    logOutcome(context, VoipOutcome.Reason.NOT_PUBLISHED, recordedMs, bytes)
                }
            }
            uri
        }

        // The Home list reads CallVault's own catalog, not the folder — a file that is never recorded
        // here exists on disk but is invisible in the app. The carrier path does this from
        // RecordingForegroundService, which the VoIP path deliberately does not go through.
        if (saf != null && published != null) {
            val safUri = published
            val name = finalName(saf.displayName.substringAfterLast('/'))
            val outcomeLogged = java.util.concurrent.atomic.AtomicBoolean(false)
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val size = SafHelper.fileSize(context, safUri)
                    // The same too-short check the carrier path makes, through the same policy, before
                    // this path's own catalog write. An app call reaches Home by a completely separate
                    // route, so a guard added only over there would leave WhatsApp misdials piling up
                    // for anyone who had turned the setting on.
                    val minSeconds = AppPreferences(context).getMinDurationSeconds()
                    if (minSeconds > 0) {
                        val durationMs = CallEndLog.timedProbe(context, "VoIP", name) {
                            AudioDecoder.durationMs(context, safUri)
                        }
                        if (MinDurationPolicy.shouldDiscard(durationMs, minSeconds)) {
                            AppLogger.i(
                                TAG,
                                "Discarding VoIP '$name': ${durationMs}ms is under the ${minSeconds}s minimum."
                            )
                            outcomeLogged.set(true)
                            logOutcome(context, VoipOutcome.Reason.TOO_SHORT, durationMs, size)
                            SafHelper.deleteDocument(
                                DocumentFile.fromSingleUri(context, safUri),
                                "the too-short VoIP recording '$name'"
                            )
                            return@runCatching
                        }
                    }
                    outcomeLogged.set(true)
                    logOutcome(context, VoipOutcome.Reason.SAVED, recordedMs, size.takeIf { it >= 0L })
                    // Keep or delete after the call: decided once, here, before the catalog row, so no
                    // sweep can copy or transcribe it in between. A hold that cannot be stored falls back
                    // to keeping, as on the carrier path.
                    val isHeld = AfterCallDecision.tryHold(context, name, safUri, codecMime, isVoip = true)
                    try {
                        CallEndLog.record(context, CallEndLog.catalogStart(name, size))
                        val catalogStartedAt = android.os.SystemClock.elapsedRealtime()
                        val catalogFailure =
                            RecordingCatalog.recordLocalReporting(context, name, safUri, size, System.currentTimeMillis())
                        CallEndLog.record(
                            context,
                            CallEndLog.catalogEnd(name, size, android.os.SystemClock.elapsedRealtime() - catalogStartedAt, catalogFailure)
                        )
                        // Marks collected during the call, keyed to the final name — the same rule the
                        // carrier path follows and for the same reason.
                        FlagRepository.save(context, name, PendingFlags.drain())
                        // Collect the speaker turns the capture just measured, before transcription is
                        // queued and before the details file below — the runner reads them from the
                        // database when it labels segments, and the details file now carries them too
                        // (issue #50), so both must run after this or they would see no turns.
                        //
                        // The capture interleaves L=near, R=far and had that separation all along; it was
                        // simply thrown away at the downmix, so app calls came out with no speaker labels
                        // while carrier calls had them. `outgoing = false`: an app call has no reliable
                        // direction here, and guessing wrong would teach the You/Them mapping the wrong
                        // side, which is worse than leaving the sides neutral. Still false when the
                        // direction is known (BabApps item 113): a stored outgoing=true row is read by
                        // trustedMap's recentOutgoing, which would feed an app call's channels into the
                        // map learned from carrier calls. Needs the owner's decision before it changes.
                        runCatching {
                            SpeakerTurnsRepository.collectAfterCall(context, name, outgoing = false)
                        }.onFailure { AppLogger.w(TAG, "Could not collect VoIP speaker turns: ${it.message}") }
                        // Same details file as the carrier path, derived the same way from the same
                        // final name. `direction` comes out null here and that is correct rather than
                        // lazy: an app call has no reliable direction, and BCR's format already has a
                        // null for a field that cannot be determined. Written after the turns above so
                        // the `callvault` block can carry them (issue #50).
                        run {
                            val parsed = RecordingsRepository.parseName(name)
                            MetadataSidecar.writeIfEnabled(
                                context = context,
                                folderUri = AppPreferences(context).getRecordingFolderUri(),
                                audioName = name,
                                timestampUnixMs = parsed.startedAtMillis ?: System.currentTimeMillis(),
                                packageName = null,
                                direction = VoipDirection.nameToken(callDirection),
                                phoneNumber = parsed.number,
                                contactName = parsed.contactName,
                                durationSecsEncoded = null,
                                // The app call's own stereo puts the near party on the left (channel A) by
                                // construction, so unlike a carrier call this never has to be learned.
                                nearChannel = "A"
                            )
                        }
                        // Same order as the carrier path: catalog first, then queue. Not while a keep-or-delete
                        // answer is pending: AfterCallDecision.keep queues it then.
                        if (!isHeld) TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name)
                        // Draw it now rather than when the user opens it: this phone has just come
                        // off a call, and they have not asked to wait for anything yet.
                        WaveformDrawWorker.enqueue(context, name, safUri)
                        // SafHelper.fileSize() returns -1 specifically for "unknown" (a provider that can't
                        // report a length right now), never for "empty" — that's 0. CallOutcomes.of() cannot
                        // tell the two apart and would judge a negative size as EMPTY_FILE, so an unknowable
                        // size must never reach it: recording nothing is the safe direction, not guessing.
                        if (size < 0L) {
                            AppLogger.i(TAG, "VoIP file size unknown for '$name' (SAF reported $size); skipping the setup-health write")
                        } else {
                            runCatching {
                                val outcome = CallOutcomes.of(size, daemonDied = false, farPartyHeard = farHeard)
                                SetupHealthStore(context).record(
                                    outcome, System.currentTimeMillis(), name, SetupFingerprint.of(AppPreferences(context))
                                )
                                // Tell the user the app call was recorded, the same way a carrier call
                                // does. Until now this path said nothing at all: the toast and vibration
                                // hang off RecordingServiceState, which only RecordingForegroundService
                                // drives, and this path deliberately does not go through it. A silent
                                // success is indistinguishable from a silent failure, and the only way to
                                // tell them apart was to go looking for the file days later.
                                //
                                // Only on Verified. Confirming a recording that is empty or one-sided
                                // would be worse than saying nothing — those already raise their own
                                // error notification, and two contradictory signals is not a fix.
                                if (outcome is CallOutcome.Verified) {
                                    RecordingNotificationHelper(context).showRecordingEnded()
                                }
                            }.onFailure { AppLogger.w(TAG, "Could not record setup health for '$name': ${it.message}") }
                        }
                        AppLogger.i(TAG, "VoIP recording catalogued: $name ($size bytes)")
                        // The Drive copy and the details-file copy wait for the answer too; Keep runs them.
                        if (isHeld) return@runCatching
                        StorageRouter.route(context, safUri, name, codecMime)
                        // Route the .json details to Drive too (issue #49). The sidecar was written
                        // earlier in this block, so it exists by now. IMMEDIATE only; the sweep handles
                        // DAILY/WEEKLY; no-op if the user did not ask for a details file. The .md transcript
                        // is routed later, from the transcription-complete hook, since it does not exist yet.
                        StorageRouter.routeSidecar(context, name, MetadataSidecar.sidecarNameFor(name), "application/json")
                    } finally {
                            // Asked last, once the catalog row exists, and in a finally so a step above that
                            // throws can never leave a held recording with no question.
                            if (isHeld) AfterCallDecision.ask(context, name)
                    }
                }.onFailure {
                    AppLogger.e(TAG, "Cataloguing the VoIP recording failed: ${it.message}", it)
                    if (!outcomeLogged.getAndSet(true)) logOutcome(context, VoipOutcome.Reason.SAVED_UNCHECKED, recordedMs, null)
                }
            }
        }
        AppLogger.i(TAG, "VoIP recording stopped (${saf?.uri})")
        // Same check as after a carrier recording: a stuck indicator with nothing recording. See [MicOpAutoHeal].
        MicOpAutoHeal.scheduleAfterCall(context, "the VoIP recording")
    }

    /**
     * An app call kept in waiting storage (review N5 / 2026-11-20) gets the after-call steps a saved one gets, from the
     * same parts: the minimum-duration setting, the marks and speaker turns (only available now), the health card, and
     * its one outcome line. The details file, the keep-or-delete question, the library entry, transcription and the
     * Drive copy follow when it is saved into a folder (WaitingRecordingsWorker).
     */
    private fun keepWaiting(context: Context, file: File, name: String, mime: String, recordedMs: Long?, farHeard: Boolean, direction: RecordingDirection?) {
        val size = file.length()
        CoroutineScope(Dispatchers.IO).launch {
            val kept = runCatching { WaitingCallSteps.atCallEnd(context, file, name, "VoIP", outgoingForTurns = false) }
                .onFailure { AppLogger.e(TAG, "After-call steps of the waiting app call failed; keeping it: ${it.message}", it) }
                .getOrDefault(true)
            if (!kept) {
                logOutcome(context, VoipOutcome.Reason.TOO_SHORT, recordedMs, size)
                return@launch
            }
            logOutcome(context, VoipOutcome.Reason.WAITING_FOR_FOLDER, recordedMs, size)
            runCatching {
                val outcome = CallOutcomes.of(size, daemonDied = false, farPartyHeard = farHeard)
                SetupHealthStore(context).record(outcome, System.currentTimeMillis(), name, SetupFingerprint.of(AppPreferences(context)))
            }.onFailure { AppLogger.w(TAG, "Could not record setup health for the waiting '$name': ${it.message}") }
            WaitingRecordings.ready(
                context, file, mime, "app call",
                WaitingNote.Extras(isVoip = true, appCallMix = false, direction = VoipDirection.nameToken(direction)),
            )
        }
    }

    /** The always-on outcome line of this app call (review N8). Never throws. */
    private fun logOutcome(context: Context, reason: VoipOutcome.Reason, recordedMs: Long? = null, sizeBytes: Long? = null) {
        CallEndLog.record(context, VoipOutcome.line(reason, recordedMs, sizeBytes))
    }

    /**
     * Throws away a started app-call recording, with its outcome line (review N8). The only place a staged
     * recording is deleted without being published, so no discard goes unrecorded (VoipOutcomeTest guards it).
     */
    private fun discardStarted(context: Context, saf: SafHelper.SafResult?, reason: VoipOutcome.Reason, recordedMs: Long?, what: String) {
        logOutcome(context, reason, recordedMs, saf?.stagingFile?.takeIf { it.exists() }?.length())
        runCatching { saf?.descriptor?.close() }
        saf?.stagingFile?.let { deleteStaged(it, what) }
        // Written straight to the folder on a build without staging: the document exists and must go.
        saf?.uri?.let { uri ->
            runCatching { SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, uri), what) }
        }
    }

    /** A published or discarded staged recording, with its note (N19). Never throws; a failure is logged. */
    private fun deleteStaged(staged: File, what: String) {
        runCatching { StagingNote.deleteWithNote(staged) }
            .onFailure { AppLogger.w(TAG, "Could not delete the staged file of $what (${staged.name}): ${it.message}") }
            .onSuccess { gone -> if (!gone) AppLogger.w(TAG, "The staged file of $what (${staged.name}) could not be deleted; the rescue sweep will look at it") }
    }

    /**
     * `<timestamp>_voip.<ext>` — no number and no contact, because a VoIP call provides neither. The
     * `voip` marker keeps these distinguishable from carrier recordings at a glance and in sorting.
     */
    /**
     * `<timestamp>_voip[_<App>][_<caller>]`. Both extras are best-effort — a VoIP call provides neither
     * a number nor a call-log entry, so an absent app or name simply drops out of the name rather than
     * being guessed at.
     */
    private fun buildFileName(codec: ScrcpyAudioCodec, appLabel: String?, caller: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.CANADA).format(Date())
        // The app hangs off the marker ("_voip-Signal") rather than sitting in its own underscore slot.
        // With both in underscore slots, a call with an app but no caller was indistinguishable from one
        // with a caller but no app, and Signal calls lost their app badge because of it.
        val app = appLabel?.takeIf { it.isNotBlank() }?.let { "-${sanitiseForFileName(it)}" } ?: ""
        val who = caller?.takeIf { it.isNotBlank() }?.let { "_$it" } ?: ""
        return "${stamp}_voip$app$who${codec.containerExtension}"   // containerExtension has the dot
    }

    /**
     * The package on the call, from the uid that owns the call's audio track.
     *
     * `getPackagesForUid` rather than string matching on a dump: it is the framework's own answer, and
     * it stays correct for shared uids and for work profiles, where the uid encodes the user id.
     * Several packages can share a uid, in which case the launchable one is the app the user is in.
     */
    /**
     * The name of the app whose call is currently up, or null when it cannot be told.
     *
     * Used by the "Ask me" prompt so it can say *which* app is calling. Resolution goes through the
     * daemon (only it can see who owns the call audio), so this returns null whenever the daemon is
     * not connected — the prompt then falls back to generic wording rather than not appearing.
     */
    /** Whether the app call now under way is with someone on the ignore list (#51) — for the "Ask me" prompt. */
    fun isCurrentCallerIgnored(context: Context): Boolean {
        val service = RecorderConnection.service ?: return false
        val pkg = runCatching { resolveCallPackage(context, service) }.getOrNull() ?: return false
        val caller = runCatching { service.voipCallerName(pkg) }.getOrNull()
        return VoipIgnoredCallers.isIgnored(context, caller)
    }

    fun currentCallAppLabel(context: Context): String? {
        val service = RecorderConnection.service ?: return null
        val pkg = runCatching { resolveCallPackage(context, service) }.getOrNull() ?: return null
        return appLabelFor(context, pkg)
    }

    /**
     * [stage] is only for the log line: "start", "late" (askAgainForApp) or "prompt".
     *
     * A uid that is not a calling app (System UI and the like) is rejected here and the daemon asked
     * again with it excluded, so the same wrong uid is never retried and never reaches the caller
     * scrape, the per-app exclude list or the "Ask me" label (BabApps item 103).
     */
    private fun resolveCallPackage(context: Context, service: IRecorderService, stage: String = "prompt"): String? {
        val pm = context.packageManager
        val resolved = VoipCallAppFilter.resolve(
            stage = stage,
            unknownUid = UID_UNKNOWN,
            askUid = { rejected -> askDaemonForCallUid(service, rejected) },
            packagesFor = { uid ->
                runCatching { pm.getPackagesForUid(uid) }.getOrNull().orEmpty().map { name ->
                    val system = runCatching {
                        (pm.getApplicationInfo(name, 0).flags and
                            (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    }.getOrDefault(false)
                    VoipCallAppFilter.PackageFacts(name, system, pm.getLaunchIntentForPackage(name) != null)
                }
            },
            log = { AppLogger.i(TAG, it) },
        )
        if (resolved == null) {
            AppLogger.d(TAG, "No VoIP call app found ($stage); naming by time alone")
            return null
        }
        val packages = resolved.packages
        return (packages.firstOrNull { it.hasLauncher } ?: packages.first()).name
    }

    /** The daemon's uid, excluding [rejected]; an older daemon without the method gets the plain call. */
    private fun askDaemonForCallUid(service: IRecorderService, rejected: Set<Int>): Int {
        if (rejected.isEmpty()) {
            // Nothing to exclude yet: the plain call works on every daemon.
            return runCatching { service.voipCallAppUid() }
                .onFailure { AppLogger.d(TAG, "call-uid lookup failed: ${it.message}") }
                .getOrDefault(UID_UNKNOWN)
        }
        val uid = runCatching { service.voipCallAppUidSkipping(rejected.toIntArray()) }
            .onFailure { AppLogger.d(TAG, "call-uid lookup (skipping) failed: ${it.message}") }
            .getOrDefault(UID_UNKNOWN)
        // A daemon from an older APK may answer a no-op with 0; that is "no answer", not an app.
        return if (uid >= 10_000) uid else UID_UNKNOWN
    }

    /** The app's user-visible name, e.g. "com.whatsapp" -> "WhatsApp"; falls back to the package. */
    private fun appLabelFor(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun sanitiseForFileName(s: String) = s.replace(Regex("""[/\\:*?"<>|_\p{Cntrl}]"""), "").trim()
}
