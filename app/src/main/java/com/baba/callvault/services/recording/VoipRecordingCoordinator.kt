/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.health.CallOutcome
import com.baba.callvault.data.health.CallOutcomes
import com.baba.callvault.data.health.Prerequisite
import com.baba.callvault.data.health.SetupFingerprint
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.health.record
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderServiceImpl
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.waveform.RecordingExtrasRepository
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
 * carrier call's metadata (number, direction, ignore-rules), none of which exists here.
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

    /** True while a VoIP recording is running, so the UI can say so. */
    val isRecording: Boolean get() = recording
    @Volatile private var pending: SafHelper.SafResult? = null
    /** A caller learned after the recording started; goes into the name at publish. See [VoipLateCaller]. */
    @Volatile private var lateCaller: String? = null
    private var lateCallerJob: Job? = null

    /** "Start when they answer": the poll that holds the encode until the app's call timer runs. */
    private var answerHoldJob: Job? = null
    /** True while the encode is held for the answer; the user's own pause is [paused]. */
    @Volatile private var heldForAnswer = false
    @Volatile private var codecMime: String = "audio/ogg"

    /** Starts a VoIP recording. No-op when the feature is off, already recording, or unavailable. */
    @Synchronized
    fun onCallStarted(context: Context) {
        if (recording) return
        val prefs = AppPreferences(context)
        if (!prefs.isVoipRecordingEnabled()) return

        val service = RecorderConnection.service
        if (service == null) {
            // Nothing can be done for THIS call, and that is not a shortcoming of the retry that is
            // missing here — see [reportMissed]. All that is left is to say so.
            reportMissedIfReal(context, R.string.voip_missed_not_ready, null)
            AppLogger.w(TAG, "VoIP call detected but the daemon is not connected — not recording")
            return
        }

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
        val callPackage = resolveCallPackage(context, service)
        val appLabel = callPackage?.let { appLabelFor(context, it) }
        val caller = callPackage?.let {
            runCatching { service.voipCallerName(it) }
                .onFailure { AppLogger.d(TAG, "caller lookup failed: ${it.message}") }
                .getOrNull()
        }
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
            return
        }

        val folderUri = prefs.getRecordingFolderUri()
        if (!SafHelper.isFolderValid(context, folderUri)) {
            // User-owned and permanent until they fix it: every call goes the same way until then.
            reportMissedIfReal(context, R.string.voip_missed_no_folder, Prerequisite.RECORDING_FOLDER)
            AppLogger.e(TAG, "VoIP call detected but the recording folder is missing/unwritable")
            return
        }

        val fileName = buildFileName(codec, appLabel, caller)

        val saf = SafHelper.createAudioFile(context, folderUri, fileName, codec.mimeType)
        if (saf == null) {
            reportMissedIfReal(context, R.string.voip_missed_no_folder, Prerequisite.RECORDING_FOLDER)
            AppLogger.e(TAG, "Could not create the VoIP output file")
            return
        }

        val started = runCatching {
            service.startVoipRecording(codec.cliKey, bitRate, saf.descriptor)
        }.onFailure { AppLogger.e(TAG, "startVoipRecording threw: ${it.message}", it) }.getOrDefault(false)

        if (!started) {
            // Most likely the policy was not armed before the call — nothing can be captured now, so
            // remove the empty file rather than leaving a 0-byte recording in the user's folder.
            reportMissedIfReal(context, R.string.voip_missed_not_ready, null)
            AppLogger.e(TAG, "VoIP recording refused by the daemon; discarding the empty file")
            runCatching { saf.descriptor.close() }
            // Nothing to delete in the user's folder: the destination is not created until a recording
            // has actually been made. Only the staging file exists, and it holds nothing.
            runCatching { saf.stagingFile?.delete() }
            return
        }

        recording = true
        pending = saf
        codecMime = codec.mimeType
        // A timeline and a mark buffer for this call, then the controls. Before this an app call
        // could be recorded from start to finish with nothing to stop it and nothing to mark.
        clock.start()
        PendingFlags.beginCall()
        currentAppLabel = appLabel
        paused = false
        isSuspendedForCarrierCall = false
        VoipRecordingNotification.show(context, appLabel)
        AppLogger.i(TAG, "VoIP recording started -> $fileName")
        lateCaller = null
        lateCallerJob?.cancel()
        // The notification is the only place the name exists, and it may not be posted yet at the
        // instant the audio mode flipped. Ask again a few times into the call; the file is only
        // published at the end, so a late answer still names it.
        if (caller == null && callPackage != null) lateCallerJob = askAgainForCaller(callPackage)
        routeJob?.cancel()
        routeJob = reportAudioRoute(context, callPackage)
        answerHoldJob?.cancel()
        heldForAnswer = false
        // The capture had to open now; what waits for the pickup is the encode. See VoipAnswerHold.
        if (callPackage != null && prefs.isRecordFromAnswerEnabled()) answerHoldJob = holdUntilAnswered(context, callPackage)
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

    private fun holdUntilAnswered(context: Context, callPackage: String): Job = CoroutineScope(Dispatchers.IO).launch {
        val startedAt = System.currentTimeMillis()
        var polls = 0
        var seenRinging = false
        while (recording) {
            val answered = runCatching { RecorderConnection.service?.voipCallAnswered(callPackage) }.getOrNull()
            val elapsed = System.currentTimeMillis() - startedAt
            polls++
            if (answered == RecorderServiceImpl.VOIP_RINGING) seenRinging = true
            if (VoipAnswerHold.decide(answered, elapsed, seenRinging) == VoipAnswerHold.Decision.RELEASE) {
                val why = when {
                    answered == RecorderServiceImpl.VOIP_ANSWERED -> "call answered"
                    elapsed >= VoipAnswerHold.MAX_HOLD_MS -> "held ${elapsed / 1000}s without an answer"
                    answered == null -> "the host has no answer to give (older daemon?)"
                    answered == RecorderServiceImpl.VOIP_NO_TIMER -> "this app's notification shows no call timer"
                    else -> "no call notification appeared within ${elapsed / 1000}s"
                }
                if (heldForAnswer) {
                    holdEncode(context, false)
                    AppLogger.i(TAG, "App-call recording released after $polls polls: $why (${elapsed}ms)")
                } else {
                    AppLogger.i(TAG, "App-call recording not held: $why")
                }
                return@launch
            }
            if (!heldForAnswer) {
                holdEncode(context, true)
                AppLogger.i(TAG, "App-call recording held until the call is answered")
            }
            delay(VoipAnswerHold.POLL_MS)
        }
    }

    /**
     * Holds or releases the encode for the answer wait, through the same binder pause the user's
     * Pause button uses. The user's own pause outranks a release: a recording they paused while it
     * was held stays paused when the call is answered.
     */
    @Synchronized
    private fun holdEncode(context: Context, hold: Boolean) {
        if (!recording) return
        heldForAnswer = hold
        if (!hold && paused) return
        runCatching { RecorderConnection.service?.setVoipPaused(hold) }
            .onFailure { AppLogger.w(TAG, "setVoipPaused for the answer hold failed: ${it.message}") }
        if (hold) clock.pause() else clock.resume()
        VoipRecordingNotification.show(context, currentAppLabel, PendingFlags.count(), paused || hold)
    }

    private fun askAgainForCaller(callPackage: String): Job = CoroutineScope(Dispatchers.IO).launch {
        var elapsed = 0L
        for (at in VoipLateCaller.RETRY_DELAYS_MS) {
            delay(at - elapsed)
            elapsed = at
            if (!recording) return@launch
            val service = RecorderConnection.service ?: return@launch
            val found = runCatching { service.voipCallerName(callPackage) }.getOrNull()
            if (found != null) {
                lateCaller = found
                AppLogger.i(TAG, "Caller name found ${at / 1000} s into the call")
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
            RecordingNotificationHelper(context).showErrorNotification(context.getString(messageRes))
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
        if (!pause && heldForAnswer) {
            heldForAnswer = false
            answerHoldJob?.cancel()
            answerHoldJob = null
            AppLogger.i(TAG, "App-call recording released by the user before the answer")
        }
        // The clock follows the encode, or every mark placed after a pause lands late in the file by
        // the length of that pause — the same rule the carrier path follows.
        if (pause) clock.pause() else clock.resume()
        VoipRecordingNotification.show(context, currentAppLabel, PendingFlags.count(), paused)
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
    @Synchronized
    fun onCallEnded(context: Context) {
        if (!recording) return
        recording = false
        val saf = pending
        pending = null
        lateCallerJob?.cancel()
        lateCallerJob = null
        answerHoldJob?.cancel()
        answerHoldJob = null
        routeJob?.cancel()
        routeJob = null
        // Ending while still held means nobody picked up: whatever the file holds is the moment
        // before the hold engaged, not a conversation. Discarded rather than published, so an
        // unanswered app call leaves nothing behind — the same as an unanswered phone call.
        val unanswered = heldForAnswer
        heldForAnswer = false
        val caller = lateCaller
        lateCaller = null
        // Straight away, not after the file work below: the controls describe a recording that has
        // already stopped, and a Stop button that lingers invites a second press at a moment when
        // the next call may already be starting.
        VoipRecordingNotification.dismiss(context)
        clock.reset()
        currentAppLabel = null
        paused = false
        isSuspendedForCarrierCall = false
        runCatching { RecorderConnection.service?.stopRecording() }
            .onFailure { AppLogger.w(TAG, "stopRecording failed: ${it.message}") }
        // The capture's own account of how its two sides lined up, written to the APP's log — the
        // same route the carrier path uses — so a report carries it even when the host's ring was
        // not on for the whole call. Issue #41 is diagnosed from this line. Empty from an older host.
        runCatching { RecorderConnection.service?.captureDiagnostics() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { AppLogger.i(TAG, "App-call capture health: $it") }

        // A recording where the far party was never audible is one-sided. Say so now rather than let it
        // be discovered weeks later — the app may have opted out of capture, or this OEM build may not
        // attach calls to our mix; from here the two are indistinguishable.
        val farHeard = runCatching { RecorderConnection.service?.voipFarPartyHeard() ?: true }
            .getOrDefault(true)   // no service, or an error: assume fine; never cry wolf
        // A capture that never started wrote nothing, and "the far party was not heard" is then trivially
        // true — which used to blame the other app ("it blocks capture") for our own failure to open the
        // microphone (vivo, 2026-09-28). Nothing recorded is reported as that, below; not as one-sided.
        val wroteAnything = saf?.stagingFile?.let { it.exists() && it.length() > 0L } ?: true
        if (!wroteAnything) {
            AppLogger.w(TAG, "VoIP capture never started — nothing was recorded (the recorder's reason is logged above)")
        } else if (!farHeard) {
            logOneSidedDiagnostics()
            AppLogger.w(TAG, "VoIP recording captured only your side — the other app blocks capture")
            // On vivo the far side is silenced by the phone until the user turns on vivo's own "In-app call
            // recording" (Recorder app), so point vivo users to that toggle instead of blaming the app.
            val warning = if (DeviceInfo.isVivo()) R.string.voip_one_sided_warning_vivo
                          else R.string.voip_one_sided_warning
            runCatching {
                RecordingNotificationHelper(context)
                    .showErrorNotification(context.getString(warning))
            }.onFailure { AppLogger.w(TAG, "Could not warn about the one-sided recording: ${it.message}") }
        }

        if (unanswered) {
            AppLogger.i(TAG, "App call ended before it was answered; discarding the held recording")
            runCatching { saf?.descriptor?.close() }
            runCatching { saf?.stagingFile?.delete() }
            // Written straight to the folder on a build without staging: the document exists and must go.
            saf?.uri?.let { uri ->
                runCatching { SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, uri), "the unanswered app call") }
            }
            return
        }

        // Publish now, and only now. The recording was written to app-private storage for the whole
        // call, so nothing has been visible in the user's folder until this moment — which is what
        // stops a sync tool uploading a growing or an empty file. See SafHelper.createAudioFile.
        val published: Uri? = saf?.let { result ->
            val staging = result.stagingFile
            val folder = result.folderUri
            val outName = result.fileName?.let { name -> caller?.let { VoipLateCaller.withCaller(name, it) } ?: name }
            val mime = result.mimeType
            if (staging == null || folder == null || outName == null || mime == null) {
                AppLogger.e(TAG, "VoIP recording cannot be published: staging details are missing")
                return@let null
            }
            if (!staging.exists() || staging.length() == 0L) {
                AppLogger.w(TAG, "VoIP capture produced no audio; nothing to publish")
                runCatching { staging.delete() }
                return@let null
            }
            val uri = SafHelper.publishStagedRecording(context, folder, outName, mime, staging)
            if (uri != null) {
                AppLogger.i(TAG, "Published VoIP recording (${staging.length()} bytes) -> $uri")
                runCatching { staging.delete() }
            } else {
                // Kept, not deleted: an unpublished recording on internal storage is recoverable and a
                // deleted one is not.
                AppLogger.e(TAG, "Could not publish the VoIP recording; it stays at ${staging.path}")
            }
            uri
        }

        // The Home list reads CallVault's own catalog, not the folder — a file that is never recorded
        // here exists on disk but is invisible in the app. The carrier path does this from
        // RecordingForegroundService, which the VoIP path deliberately does not go through.
        if (saf != null && published != null) {
            val safUri = published
            val name = saf.displayName.substringAfterLast('/').let { n -> caller?.let { VoipLateCaller.withCaller(n, it) } ?: n }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val size = SafHelper.fileSize(context, safUri)
                    // The same too-short check the carrier path makes, through the same policy, before
                    // this path's own catalog write. An app call reaches Home by a completely separate
                    // route, so a guard added only over there would leave WhatsApp misdials piling up
                    // for anyone who had turned the setting on.
                    val minSeconds = AppPreferences(context).getMinDurationSeconds()
                    if (minSeconds > 0) {
                        val durationMs = AudioDecoder.durationMs(context, safUri)
                        if (MinDurationPolicy.shouldDiscard(durationMs, minSeconds)) {
                            AppLogger.i(
                                TAG,
                                "Discarding VoIP '$name': ${durationMs}ms is under the ${minSeconds}s minimum."
                            )
                            SafHelper.deleteDocument(
                                DocumentFile.fromSingleUri(context, safUri),
                                "the too-short VoIP recording '$name'"
                            )
                            return@runCatching
                        }
                    }
                    RecordingCatalog.recordLocal(context, name, safUri, size, System.currentTimeMillis())
                    // Marks collected during the call, keyed to the final name — the same rule the
                    // carrier path follows and for the same reason.
                    FlagRepository.save(context, name, PendingFlags.drain())
                    // Same details file as the carrier path, derived the same way from the same
                    // final name. `direction` comes out null here and that is correct rather than
                    // lazy: an app call has no reliable direction, and BCR's format already has a
                    // null for a field that cannot be determined.
                    run {
                        val parsed = RecordingsRepository.parseName(name)
                        MetadataSidecar.writeIfEnabled(
                            context = context,
                            folderUri = AppPreferences(context).getRecordingFolderUri(),
                            audioName = name,
                            timestampUnixMs = parsed.startedAtMillis ?: System.currentTimeMillis(),
                            packageName = null,
                            direction = null,
                            phoneNumber = parsed.number,
                            contactName = parsed.contactName,
                            durationSecsEncoded = null
                        )
                    }
                    // Collect the speaker turns the capture just measured, before transcription is
                    // queued — the runner reads them from the database when it labels segments, so
                    // arriving afterwards would mean an unlabelled transcript.
                    //
                    // The capture interleaves L=near, R=far and had that separation all along; it was
                    // simply thrown away at the downmix, so app calls came out with no speaker labels
                    // while carrier calls had them. `outgoing = false`: an app call has no reliable
                    // direction here, and guessing wrong would teach the You/Them mapping the wrong
                    // side, which is worse than leaving the sides neutral.
                    runCatching {
                        SpeakerTurnsRepository.collectAfterCall(context, name, outgoing = false)
                    }.onFailure { AppLogger.w(TAG, "Could not collect VoIP speaker turns: ${it.message}") }
                    // Same order as the carrier path: catalog first, then queue.
                    TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name)
                    // Draw it now rather than when the user opens it: this phone has just come
                    // off a call, and they have not asked to wait for anything yet.
                    RecordingExtrasRepository.precomputeWaveform(context, name, safUri)
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
                    StorageRouter.route(context, safUri, name, codecMime)
                }.onFailure { AppLogger.e(TAG, "Cataloguing the VoIP recording failed: ${it.message}", it) }
            }
        }
        AppLogger.i(TAG, "VoIP recording stopped (${saf?.uri})")
        // Same check as after a carrier recording: a stuck indicator with nothing recording. See [MicOpAutoHeal].
        MicOpAutoHeal.scheduleAfterCall(context, "the VoIP recording")
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
    fun currentCallAppLabel(context: Context): String? {
        val service = RecorderConnection.service ?: return null
        val pkg = runCatching { resolveCallPackage(context, service) }.getOrNull() ?: return null
        return appLabelFor(context, pkg)
    }

    private fun resolveCallPackage(context: Context, service: IRecorderService): String? {
        val uid = runCatching { service.voipCallAppUid() }
            .onFailure { AppLogger.d(TAG, "call-uid lookup failed: ${it.message}") }
            .getOrDefault(UID_UNKNOWN)
        if (uid == UID_UNKNOWN) {
            AppLogger.d(TAG, "No VoIP call audio owner found; naming by time alone")
            return null
        }

        val pm = context.packageManager
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.toList().orEmpty()
        if (packages.isEmpty()) {
            AppLogger.d(TAG, "uid $uid resolved to no visible package")
            return null
        }
        return packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: packages.first()
    }

    /** The app's user-visible name, e.g. "com.whatsapp" -> "WhatsApp"; falls back to the package. */
    private fun appLabelFor(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    private fun sanitiseForFileName(s: String) = s.replace(Regex("""[/\\:*?"<>|_\p{Cntrl}]"""), "").trim()
}
