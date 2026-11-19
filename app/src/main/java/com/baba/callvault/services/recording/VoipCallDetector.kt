/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.storage.CutOffRescueWorker
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.Executor

/**
 * Notices VoIP calls starting and ending.
 *
 * There is no `PHONE_STATE` broadcast for WhatsApp/Signal/Telegram, so the signal used here is the
 * **audio mode**: a VoIP app puts the device into `MODE_IN_COMMUNICATION` for the duration of a call.
 * Carrier calls use `MODE_IN_CALL` instead, so the two paths cannot collide.
 *
 * The mode is deliberately the only thing inspected. An earlier version keyed off the
 * `USAGE_VOICE_COMMUNICATION` usage on [AudioPlaybackConfiguration]s, which does not work from an
 * ordinary app: the framework hands out **anonymised** playback configurations to callers without
 * `MODIFY_AUDIO_ROUTING` (only the shell-uid daemon holds that), so the real usage is never visible and
 * detection silently never fired. `AudioManager.getMode()` needs no permission and is not redacted.
 *
 * Detection is free to run late. The capture policy is armed when the feature is switched on (see
 * [VoipCaptureController]) and only the POLICY must predate the call — the sink can be created on a
 * call already in progress.
 *
 * Hosted by [DaemonKeepAliveService], already a permanent foreground service, so this costs no extra
 * process and no second notification, and VoIP gets the same lifetime as carrier recording.
 */
class VoipCallDetector(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private var registered = false

    /** True while we believe a VoIP call is up, so repeat signals don't restart the recording. */
    private var callActive = false

    /**
     * True while the app call is held open across a carrier call.
     *
     * The app call has NOT ended — it is on hold in its own app — so [callActive] deliberately stays
     * true. Clearing it would let the mode event that arrives when the phone call finishes look like
     * a brand new app call, which is precisely the behaviour that produced two files for one
     * conversation.
     */
    private var suspendedForCarrier = false

    // ---- BabApps item 107: the ring-time mode dip of an outgoing Telegram / AyuGram call ----
    /** When the current app call was detected (elapsedRealtime), for [VoipRingDipPolicy]. */
    private var callDetectedAt = 0L

    /** True once this detector has seen a non-call audio mode, so a call it then detects began while it watched. */
    private var sawNoCallSinceRegister = false

    /** True once a call-voice player (vcPlay>0) was seen in the current call. */
    private var voicePlayerSeen = false

    /** True while the pending end was scheduled as a ring-time dip (long wait). */
    private var endDeferredAsRingDip = false

    // ---- BabApps item 108: an app call that never leaves MODE_NORMAL (LINE), see [VoipNormalModePolicy] ----
    /** True while the current call was started from its audio, not from the mode. */
    private var normalModeCall = false

    /** When the current normal-mode call was started (elapsedRealtime), for the stop line. */
    private var normalModeStartedAt = 0L

    /** Since when a call-voice player AND a call-voice recording have both been live in NORMAL mode; 0 = not. */
    private var normalCandidateSince = 0L

    /** The pair was looked at for this candidate already, so it is not looked at again until it ends. */
    private var normalCandidateJudged = false

    /** Judges of the current candidate so far (review C9), capped by [VoipNormalModePolicy.MAX_JUDGE_TRIES]. */
    private var normalJudgeTries = 0

    /** A judge found another lookup running: it is run again the moment that lookup finishes (C9). */
    private var normalJudgePending = false

    /** The last "voice audio in normal mode" signature that was logged, so a line is written once per change. */
    private var lastNormalVoiceKey: String? = null
    private val ownersLookupBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Set while the mode is in the IN_COMMUNICATION stretch of a carrier call (a Samsung Wi-Fi call).
     * See [VoipStrayStartPolicy]: without it the hang-up left a stray 1 s `_voip` file (#45).
     */
    private var carrierEpisode = VoipStrayStartPolicy.Latch()

    /** Looks again when a carrier-episode tail ends, in case an app call carried on through it. */
    private val tailRecheck = Runnable { evaluate(audioManager?.mode ?: AudioManager.MODE_NORMAL) }


    /** Set while a recording is running, so the host can reflect it in its notification. */
    @Volatile
    var isRecording: Boolean = false
        private set

    /** Notified when [isRecording] changes, so the keep-alive notification can show the state. */
    var onRecordingStateChanged: (() -> Unit)? = null

    init {
        // A start that first settled a leftover recorder session off the main thread finishes later (C2).
        VoipRecordingCoordinator.onDeferredStartFinished = {
            isRecording = VoipRecordingCoordinator.isRecording
            onRecordingStateChanged?.invoke()
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        evaluate(mode)
        probe("mode")
    }

    // ---- #53 diagnostic: logs only, decides nothing (see [VoipEndProbe]) ----
    private var lastProbe: VoipEndProbe.Snapshot? = null
    private var probeTailUntil = 0L
    private var heartbeatScheduled = false

    /** Logs the end-of-call signals when they change, while a call is up and for a while after. */
    private fun probe(reason: String, always: Boolean = false) {
        val am = audioManager ?: return
        if (!callActive && !carrierEpisode.active && android.os.SystemClock.elapsedRealtime() > probeTailUntil) return
        val now = runCatching { VoipEndProbe.capture(am) }.getOrNull() ?: return
        if (callActive && now.voiceCallPlayers > 0) voicePlayerSeen = true
        if (always || VoipEndProbe.isWorthLogging(lastProbe, now)) {
            AppLogger.i(TAG, "endprobe[$reason] callActive=$callActive ${VoipEndProbe.describe(now)}")
        }
        lastProbe = now
        if (!heartbeatScheduled) {
            heartbeatScheduled = true
            handler.postDelayed(probeHeartbeat, PROBE_HEARTBEAT_MS)
        }
    }

    /** Shows the mode still being held (or not) when nothing changes — the Honor symptom. */
    private val probeHeartbeat: Runnable = Runnable {
        heartbeatScheduled = false
        probe("tick", always = true)
        // A carrier call that ends with no audio event would otherwise leave the latch up.
        if (carrierEpisode.active) evaluate(audioManager?.mode ?: AudioManager.MODE_NORMAL)
        // A normal-mode call has no mode event to end it: the heartbeat is its backstop (item 108).
        if (normalModeCall) watchNormalMode()
    }

    /**
     * Secondary trigger for API 30, where [AudioManager.OnModeChangedListener] does not exist. The
     * configurations themselves are ignored (they are anonymised for us) — this only says "something
     * about playback changed, go re-read the mode".
     */
    private val playbackTrigger = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            evaluate(audioManager?.mode ?: AudioManager.MODE_NORMAL)
            probe("playback")
            watchNormalMode()
        }
    }

    /** The recording side of the same watch: a call-voice recording opening or closing (item 108). */
    private val recordingTrigger = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            watchNormalMode()
        }
    }

    /** A call's mode can wobble briefly on route changes, so ending is debounced. */
    private val endCallRunnable = Runnable {
        if (callActive) {
            callActive = false
            suspendedForCarrier = false
            handler.removeCallbacks(suspendTimeout)
            AppLogger.i(TAG, "VoIP call ended")
            if (normalModeCall) logNormalModeStopped()
            normalModeCall = false
            handler.removeCallbacks(normalEndRunnable)
            probeTailUntil = android.os.SystemClock.elapsedRealtime() + PROBE_TAIL_MS
            probe("ended", always = true)
            // Take the prompt down with the call. Leaving it would offer to record a call that is
            // over, and tapping it would start a recording of nothing.
            VoipRecordPrompt.cancel(context)
            val neverConnected = VoipRingDipPolicy.neverConnected(endDeferredAsRingDip, voicePlayerSeen)
            endDeferredAsRingDip = false
            runCatching { VoipRecordingCoordinator.onCallEnded(context, neverConnected) }
                .onFailure { AppLogger.w(TAG, "VoIP stop failed: ${it.message}") }
            CutOffRescueWorker.schedule(context)
            // The app call is over: a recording no process owns (left by a CallVault that was killed during this
            // call) is stopped now rather than at the rescue check, and a call-free moment is noted only if no
            // phone call is up either (C2).
            OrphanRecorderSession.settleAsync(context, "app call ended")
            isRecording = false
            onRecordingStateChanged?.invoke()
        }
    }

    /**
     * The debounced end: [endCallRunnable] only if the mode still says the call is over.
     *
     * Scheduled on a mode change, carried out 1.5 s later. The mode is read again at that point,
     * because a mode that dipped and came back is a call still in progress.
     */
    private val debouncedEndRunnable = Runnable {
        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        if (!VoipSwitchPolicy.stillEnded(mode, callActive, suspendedForCarrier)) {
            AppLogger.i(TAG, "The audio mode came back (mode=$mode) — the app call is still up, recording continues")
            return@Runnable
        }
        endCallRunnable.run()
    }

    /**
     * Last resort. A held recording keeps an encoder, a muxer and the output fd open the whole time,
     * so a signal that never arrives would leak all three and lose the recording outright. If the
     * phone call somehow never reports finishing, finalise what we have.
     *
     * Declared after [endCallRunnable] because it calls it: the other order is a forward reference
     * that Kotlin rejects outright.
     */
    private val suspendTimeout: Runnable = Runnable {
        if (suspendedForCarrier) {
            AppLogger.w(TAG, "App call held for a carrier call for too long — finalising what we have")
            endCallRunnable.run()
        }
    }

    /**
     * Starts recording a call already under way — the answer to the "Ask me" prompt.
     *
     * Ignored unless a call is actually up: the prompt is dismissable and the action could arrive
     * late, and starting a capture with no call would produce a file of silence.
     */
    fun startNow() {
        val decision = VoipManualStartPolicy.decide(
            callActive, isRecording, normalModeCall, carrierCallUp = !VoipTelephonyGate.mayStartNow(context),
        )
        if (decision == VoipManualStartPolicy.Decision.RefuseCarrierCall) {
            CallEndLog.record(context, "app call: Record tapped during a phone call; not starting the app-call capture (it would cut your side of the phone call)")
            VoipRecordPrompt.cancel(context)
            return
        }
        if (decision !is VoipManualStartPolicy.Decision.Start) {
            AppLogger.i(TAG, "Manual app-call start ignored (callActive=$callActive recording=$isRecording)")
            VoipRecordPrompt.cancel(context)
            return
        }
        AppLogger.i(TAG, "Recording this app call on the user's request (normal-mode=${decision.viaNormalMode})")
        VoipRecordPrompt.cancel(context)
        // The call's own kind (review C26): a LINE call recognised in MODE_NORMAL (item 108) starts as one.
        runCatching { VoipRecordingCoordinator.onCallStarted(context, manual = true, viaNormalMode = decision.viaNormalMode) }
            .onFailure { AppLogger.w(TAG, "Manual VoIP start failed: ${it.message}") }
        isRecording = VoipRecordingCoordinator.isRecording
        onRecordingStateChanged?.invoke()
    }

    /**
     * Ends an app-call recording because a carrier call has been answered, so the carrier call is recorded.
     *
     * The mode listener cannot be relied on for this: on a ROM where an IMS carrier call reads as
     * MODE_IN_COMMUNICATION, the mode never changes when the phone call starts, so nothing would fire
     * and the capture would run on through it. The telephony broadcast is the signal that does arrive.
     */
    fun abortForCarrierCall() {
        if (!callActive) return
        handler.removeCallbacks(debouncedEndRunnable)
        endDeferredAsRingDip = false

        // End it, never hold it (review C1, interim). Holding kept the app-call recording "active" through the
        // phone call, so the carrier recorder stood down and the recorder refused a second session: the phone
        // call was never recorded. Until the recorder can run a carrier session beside a parked app call, the
        // app call ends here and is published; returning to it after the phone call starts a new file. Two
        // files for one app conversation is the known cost; a phone call nobody records is the loss this stops.
        AppLogger.i(TAG, "A carrier call was answered — ending the app-call recording so the phone call can be recorded")
        if (normalModeCall) logNormalModeStopped()
        normalModeCall = false
        handler.removeCallbacks(normalEndRunnable)
        callActive = false
        suspendedForCarrier = false
        VoipRecordPrompt.cancel(context)
        // Off the main thread (the stop can take seconds); the phone call's start waits for it, bounded (C1).
        VoipRecordingCoordinator.endInBackground(context)
        isRecording = false
        onRecordingStateChanged?.invoke()
    }

    /**
     * Reacts to an audio-mode change.
     *
     * The decision itself lives in [VoipSwitchPolicy] so it can be tested — it has four states and
     * three modes, and none of it is reachable through a real [AudioManager]. This method only
     * carries the decision out.
     */
    private fun evaluate(mode: Int) {
        if (!com.baba.callvault.services.recording.aftercall.AfterCallPolicy.isCallAudioMode(mode)) sawNoCallSinceRegister = true
        val latched = VoipStrayStartPolicy.afterMode(carrierEpisode, mode)
        if (carrierEpisode.active && !latched.active) {
            AppLogger.i(TAG, "Carrier episode over (mode=${VoipEndProbe.modeName(mode)}); app-call starts allowed again")
            probeTailUntil = android.os.SystemClock.elapsedRealtime() + PROBE_TAIL_MS
        }
        carrierEpisode = latched
        if (normalModeCall) {
            if (mode == AudioManager.MODE_IN_COMMUNICATION) {
                // The app moved into call mode after all: from here the mode-driven rules own this call.
                normalModeCall = false
                handler.removeCallbacks(normalEndRunnable)
                AppLogger.i(TAG, "The normal-mode app call entered call mode — the mode now drives it")
            } else if (callActive) {
                return   // watchNormalMode() drives the end of this call
            }
        }
        when (VoipSwitchPolicy.decide(mode, callActive, suspendedForCarrier)) {
            VoipSwitchPolicy.Action.NOTHING -> return

            // A phone call is still up and an app call is held behind it. Waiting is the whole point.
            VoipSwitchPolicy.Action.HOLD -> return

            VoipSwitchPolicy.Action.RESUME -> {
                handler.removeCallbacks(suspendTimeout)
                handler.removeCallbacks(debouncedEndRunnable)
                suspendedForCarrier = false
                runCatching { VoipRecordingCoordinator.setSuspendedForCarrierCall(context, false) }
                    .onFailure { AppLogger.w(TAG, "VoIP resume failed: ${it.message}") }
                isRecording = VoipRecordingCoordinator.isRecording
                onRecordingStateChanged?.invoke()
                return
            }

            VoipSwitchPolicy.Action.END -> {
                handler.removeCallbacks(debouncedEndRunnable)
                // Read the players now: the probe that follows this event runs after it.
                runCatching { audioManager?.let { VoipEndProbe.capture(it) } }.getOrNull()
                    ?.takeIf { it.voiceCallPlayers > 0 }?.let { voicePlayerSeen = true }
                val age = android.os.SystemClock.elapsedRealtime() - callDetectedAt
                val delayMs = VoipRingDipPolicy.endDelayMs(age, voicePlayerSeen)
                endDeferredAsRingDip = VoipRingDipPolicy.isRingDip(age, voicePlayerSeen)
                if (endDeferredAsRingDip) {
                    AppLogger.i(TAG, "Audio mode left call mode ${age}ms into the call with no call-voice player seen — " +
                        "keeping the session up to ${delayMs / 1000}s in case it is the ring and the call mode returns")
                }
                handler.postDelayed(debouncedEndRunnable, delayMs)
                return
            }

            // The mode dipped and came back inside the debounce: the call never ended.
            VoipSwitchPolicy.Action.STAY -> {
                endDeferredAsRingDip = false
                if (handler.hasCallbacks(debouncedEndRunnable)) {
                    handler.removeCallbacks(debouncedEndRunnable)
                    AppLogger.i(TAG, "Audio mode dipped and came back — the app call continues")
                }
                return
            }

            VoipSwitchPolicy.Action.START -> {
                val live = audioManager?.mode ?: mode
                if (!VoipSwitchPolicy.stillStarting(live, callActive, suspendedForCarrier)) {
                    AppLogger.i(TAG, "mode=IN_COMMUNICATION was reported, but the mode is already $live — the app call is over, not recording")
                    return
                }
            }
        }
        startAppCall(viaNormalMode = false)
    }

    /**
     * Carries out a start: the carrier-call checks, then the recording (or the "Ask me" prompt).
     *
     * The body of what used to be inline in [evaluate]; [viaNormalMode] is the only difference (item 108):
     * the call was recognised from its audio, the mode is NORMAL, and the log says so.
     */
    private fun startAppCall(viaNormalMode: Boolean) {
        run {
            // The mode alone does not prove this is an app call. A carrier call carried over IMS
            // (Wi-Fi calling, some VoLTE stacks) can present as MODE_IN_COMMUNICATION too, and acting
            // on it would record a phone call the carrier path is already recording — see
            // [VoipTelephonyGate]. Deliberately NOT marking callActive: we believe no app call is up,
            // so a later mode event is free to reconsider once the phone call is over.
            val managed = !VoipTelephonyGate.mayStartNow(context)
            val now = android.os.SystemClock.elapsedRealtime()
            carrierEpisode = VoipStrayStartPolicy.afterTime(carrierEpisode, managed, now)
            val verdict = VoipStrayStartPolicy.verdict(latched = carrierEpisode.active, managedCall = managed)
            if (verdict != VoipStrayStartPolicy.Verdict.START) {
                carrierEpisode = VoipStrayStartPolicy.afterVerdict(carrierEpisode, verdict)
                VoipStrayStartPolicy.tailRemainingMs(carrierEpisode, now)?.let { wait ->
                    handler.removeCallbacks(tailRecheck)
                    handler.postDelayed(tailRecheck, wait + TAIL_RECHECK_SLACK_MS)
                }
                AppLogger.i(TAG, "${if (viaNormalMode) "normal-mode voice audio" else "mode=IN_COMMUNICATION"} but not an app call: $verdict (carrierEpisode=$carrierEpisode)")
                probe("refused")
                return
            }
            handler.removeCallbacks(debouncedEndRunnable)
            callActive = true
            callDetectedAt = android.os.SystemClock.elapsedRealtime()
            // Its beginning, if this detector saw it begin (a non-call mode before it). A call already up when the
            // detector registered is one CallVault was restarted into (Q25): the killed process's value stays (C2).
            // A normal-mode call (item 108) has no mode edge to prove a beginning, so it notes none.
            if (sawNoCallSinceRegister && !viaNormalMode) OrphanRecorderSession.noteCallBegan(context)
            // A normal-mode call is recognised BY its call-voice player, so one has been seen.
            voicePlayerSeen = viaNormalMode
            endDeferredAsRingDip = false
            normalModeCall = viaNormalMode
            normalModeStartedAt = callDetectedAt
            if (viaNormalMode) {
                CallEndLog.record(context, "normal-mode app call detected (mode=NORMAL, call-voice player + recording from an allow-listed app): starting")
            } else {
                AppLogger.i(TAG, "VoIP call detected (mode=IN_COMMUNICATION)")
            }

            // The master On/Off switch is off: the user turned recording off entirely, so a STOPPED
            // CallVault must be silent on app calls too — don't record and don't even offer to. (The
            // prompt's own Record action is gated as well, in VoipRecordingCoordinator.onCallStarted, but
            // the honest behaviour is to not raise a prompt that could not record anything.)
            if (!AppPreferences(context).getRecordingState().isRecordingAllowed) {
                AppLogger.i(TAG, "Master recording switch is off — not recording or offering this app call")
                return
            }

            // "Ask me": arm, but let the user decide. The detector still runs — it has to, or there
            // would be nothing to prompt about — and only the automatic start is withheld.
            val prefs = AppPreferences(context)
            val action = RecordingPolicy.forVoipCall(
                voipEnabled = prefs.isVoipRecordingEnabled(),
                autoStart = prefs.isVoipAutoStartEnabled(),
            )
            if (action == RecordingPolicy.VoipAction.OFFER) {
                // An ignored caller gets no prompt either (#51): asking "record this?" about someone the
                // user listed to skip would make the list mean nothing.
                if (VoipRecordingCoordinator.isCurrentCallerIgnored(context)) {
                    AppLogger.i(TAG, "App call with an ignored contact — not offering to record")
                    return
                }
                AppLogger.i(TAG, "App-call auto-start is off — offering to record instead")
                VoipRecordPrompt.show(context, VoipRecordingCoordinator.currentCallAppLabel(context))
                onRecordingStateChanged?.invoke()
                return
            }
            runCatching { VoipRecordingCoordinator.onCallStarted(context, viaNormalMode = viaNormalMode) }
                .onFailure { AppLogger.w(TAG, "VoIP start failed: ${it.message}") }
            isRecording = VoipRecordingCoordinator.isRecording
            onRecordingStateChanged?.invoke()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // BabApps item 108: app calls that never leave MODE_NORMAL
    // ---------------------------------------------------------------------------------------------

    /**
     * Called on every playback or recording change (and on the heartbeat of a normal-mode call).
     *
     * Cheap and on the main thread: it only reads the counts the app is allowed to see. The owner of the
     * audio (its package) needs the recorder host, so that lookup runs off the main thread — see
     * [lookUpOwners].
     */
    private fun watchNormalMode() {
        val am = audioManager ?: return
        val snap = runCatching { VoipEndProbe.capture(am) }.getOrNull() ?: return
        if (normalModeCall) {
            watchNormalModeCall(snap)
            return
        }
        val voiceAudio = snap.voiceCallPlayers > 0 || snap.voiceCommRecorders > 0
        if (snap.mode != AudioManager.MODE_NORMAL || !voiceAudio) {
            lastNormalVoiceKey = null
            clearNormalCandidate()
            return
        }
        val both = snap.voiceCallPlayers > 0 && snap.voiceCommRecorders > 0
        // Log once per change (callActive or not): who owns it, what it is, whether the allow-list matched. Not while
        // a candidate waits for its judge (C9): that descriptive lookup is what made the judge find one running, and
        // the judge logs the owners itself.
        val key = "${snap.voiceCallPlayers}/${snap.voiceCommRecorders}/${snap.playerKinds}"
        if (key != lastNormalVoiceKey) {
            lastNormalVoiceKey = key
            if (callActive || !both) lookUpOwners(snap, judge = false)
        }
        if (callActive || !both) {
            clearNormalCandidate()
            return
        }
        if (normalCandidateSince == 0L) {
            normalCandidateSince = android.os.SystemClock.elapsedRealtime()
            normalCandidateJudged = false
            normalJudgeTries = 0
            normalJudgePending = false
            handler.removeCallbacks(normalConfirmRunnable)
            handler.postDelayed(normalConfirmRunnable, VoipNormalModePolicy.HOLD_MS)
        }
    }

    private fun clearNormalCandidate() {
        normalCandidateSince = 0L
        normalCandidateJudged = false
        normalJudgeTries = 0
        normalJudgePending = false
        handler.removeCallbacks(normalConfirmRunnable)
    }

    /** The pair has been live for [VoipNormalModePolicy.HOLD_MS]: look at who owns it, once. */
    private val normalConfirmRunnable: Runnable = Runnable {
        val am = audioManager ?: return@Runnable
        val snap = runCatching { VoipEndProbe.capture(am) }.getOrNull() ?: return@Runnable
        val both = snap.mode == AudioManager.MODE_NORMAL && snap.voiceCallPlayers > 0 && snap.voiceCommRecorders > 0
        if (normalCandidateSince == 0L || normalCandidateJudged || callActive || !both) {
            if (!both) clearNormalCandidate()
            return@Runnable
        }
        normalCandidateJudged = true
        normalJudgeTries++
        lookUpOwners(snap, judge = true)
    }

    /**
     * Asks the recorder host who owns the call-voice audio, off the main thread, and logs the answer
     * (always-on, see [CallEndLog]). With [judge] it then decides, back on the main thread, whether this
     * is a normal-mode app call to record.
     *
     * Nothing here waits on the main thread: `dumpsys audio` takes a few hundred ms in the host.
     */
    private fun lookUpOwners(snap: VoipEndProbe.Snapshot, judge: Boolean) {
        val service = com.baba.callvault.server.RecorderConnection.service
        if (service == null) {
            CallEndLog.record(context, "normal-mode voice audio: ${VoipEndProbe.describe(snap)} callActive=$callActive — recorder host not connected, owners unknown")
            if (judge) afterJudge(VoipNormalModePolicy.JudgeFailure.HOST_NOT_CONNECTED, null, pairLiveNow())
            return
        }
        if (!ownersLookupBusy.compareAndSet(false, true)) {
            // One lookup at a time. A judge that finds one running runs again when that lookup finishes (C9).
            if (judge) afterJudge(VoipNormalModePolicy.JudgeFailure.LOOKUP_BUSY, null, pairLiveNow())
            return
        }
        Thread {
            val dump = runCatching { service.diagnosticDump("voip_owners", null) }.getOrNull()
            val pm = context.packageManager
            val packagesFor = { uid: Int -> runCatching { pm.getPackagesForUid(uid) }.getOrNull().orEmpty().toList() }
            val owners = dump?.let { VoipNormalModePolicy.parseOwners(it) }
            val players = owners?.let { VoipNormalModePolicy.voicePlayerPackages(it, packagesFor) }.orEmpty()
            val recorders = owners?.let {
                VoipNormalModePolicy.recorderPackagesOrPlayers(it, packagesFor, snap.voiceCommRecorders > 0, players)
            }.orEmpty()
            handler.post {
                ownersLookupBusy.set(false)
                val line = if (owners == null) "owners unknown (the recorder host gave no answer)"
                else VoipNormalModePolicy.describe(owners, players, recorders)
                CallEndLog.record(
                    context,
                    "normal-mode voice audio: ${VoipEndProbe.describe(snap)} callActive=$callActive $line",
                )
                when {
                    judge && owners == null -> afterJudge(VoipNormalModePolicy.JudgeFailure.NO_ANSWER, null, pairLiveNow())
                    judge -> judgeNormalCandidate(players, recorders)
                    normalJudgePending -> {
                        // A judge waited for this lookup (C9): run it now, if its candidate is still there.
                        normalJudgePending = false
                        if (normalCandidateSince != 0L && !callActive) {
                            normalCandidateJudged = false
                            handler.removeCallbacks(normalConfirmRunnable)
                            handler.post(normalConfirmRunnable)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; name = "cv-voip-owners" }.start()
    }

    /** Main thread: the policy's verdict, on the audio as it is now. */
    private fun judgeNormalCandidate(players: Set<String>, recorders: Set<String>) {
        val am = audioManager ?: return
        val snap = runCatching { VoipEndProbe.capture(am) }.getOrNull()
        if (snap == null) {
            afterJudge(VoipNormalModePolicy.JudgeFailure.NO_ANSWER, null, appSeesPair = true)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val stillBoth = snap.voiceCallPlayers > 0 && snap.voiceCommRecorders > 0
        val heldMs = if (normalCandidateSince == 0L || !stillBoth) 0L else now - normalCandidateSince
        val verdict = VoipNormalModePolicy.decide(
            mode = snap.mode,
            carrierCallUp = !VoipTelephonyGate.mayStartNow(context),
            callActive = callActive,
            vcPlayerPackages = players,
            vcRecorderPackages = recorders,
            heldMs = heldMs,
        )
        AppLogger.i(TAG, "normal-mode voice audio verdict: $verdict (held ${heldMs}ms, try $normalJudgeTries)")
        afterJudge(null, verdict, appSeesPair = stillBoth && snap.mode == AudioManager.MODE_NORMAL)
    }

    /** Whether the app itself still sees a call-voice player and recording in MODE_NORMAL. Unreadable reads as yes. */
    private fun pairLiveNow(): Boolean {
        val snap = audioManager?.let { am -> runCatching { VoipEndProbe.capture(am) }.getOrNull() } ?: return true
        return snap.mode == AudioManager.MODE_NORMAL && snap.voiceCallPlayers > 0 && snap.voiceCommRecorders > 0
    }

    /**
     * Carries out [VoipNormalModePolicy.afterJudge] for the current candidate (review C9): start, stop judging,
     * judge again, or give up and say so in the always-on log. Never drops a live candidate without a line.
     */
    private fun afterJudge(failure: VoipNormalModePolicy.JudgeFailure?, verdict: VoipNormalModePolicy.Verdict?, appSeesPair: Boolean) {
        if (normalCandidateSince == 0L || callActive) return
        val why = failure?.name?.lowercase() ?: verdict?.name?.lowercase() ?: "?"
        when (VoipNormalModePolicy.afterJudge(failure, verdict, appSeesPair, normalJudgeTries)) {
            VoipNormalModePolicy.JudgeNext.STARTED -> {
                clearNormalCandidate()
                startAppCall(viaNormalMode = true)
            }
            VoipNormalModePolicy.JudgeNext.FINAL -> Unit   // judged: not looked at again until the pair ends
            VoipNormalModePolicy.JudgeNext.RETRY_AFTER_LOOKUP -> {
                normalCandidateJudged = false
                normalJudgePending = true
                CallEndLog.record(context, "normal-mode app call check $normalJudgeTries met another lookup running; checking again when it ends")
            }
            VoipNormalModePolicy.JudgeNext.RETRY_LATER -> {
                normalCandidateJudged = false
                handler.removeCallbacks(normalConfirmRunnable)
                handler.postDelayed(normalConfirmRunnable, VoipNormalModePolicy.JUDGE_RETRY_MS)
                CallEndLog.record(context, "normal-mode app call check $normalJudgeTries gave no answer ($why); checking again in ${VoipNormalModePolicy.JUDGE_RETRY_MS} ms")
            }
            VoipNormalModePolicy.JudgeNext.GIVE_UP ->
                CallEndLog.record(context, "normal-mode app call NOT started: no usable answer after $normalJudgeTries checks (last: $why); this call is not recorded")
        }
    }

    /** A normal-mode call is up: end it once the call-voice audio has been gone for the debounce. */
    private fun watchNormalModeCall(snap: VoipEndProbe.Snapshot) {
        val ended = VoipNormalModePolicy.shouldEnd(snap.mode, snap.voiceCallPlayers, snap.voiceCommRecorders)
        if (!ended) {
            if (handler.hasCallbacks(normalEndRunnable)) {
                handler.removeCallbacks(normalEndRunnable)
                AppLogger.i(TAG, "Call-voice audio came back inside the debounce — the normal-mode call continues")
            }
            return
        }
        if (!handler.hasCallbacks(normalEndRunnable)) {
            handler.postDelayed(normalEndRunnable, VoipNormalModePolicy.END_DEBOUNCE_MS)
        }
    }

    private val normalEndRunnable: Runnable = Runnable {
        val am = audioManager ?: return@Runnable
        val snap = runCatching { VoipEndProbe.capture(am) }.getOrNull() ?: return@Runnable
        if (normalModeCall && VoipNormalModePolicy.shouldEnd(snap.mode, snap.voiceCallPlayers, snap.voiceCommRecorders)) {
            AppLogger.i(TAG, "Call-voice player and recording are both gone — ending the normal-mode call")
            endCallRunnable.run()
        }
    }

    /** The stop line of a normal-mode call. The capture's own peak levels follow in "App-call capture health". */
    private fun logNormalModeStopped() {
        val seconds = (android.os.SystemClock.elapsedRealtime() - normalModeStartedAt) / 1000
        CallEndLog.record(context, "normal-mode app call stopped after ${seconds}s; capture health and first-second peaks follow in the VoIP log")
    }

    /** Starts watching, if the feature is on. Idempotent. */
    fun start() {
        if (registered || audioManager == null) return
        if (!AppPreferences(context).isVoipRecordingEnabled()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.addOnModeChangedListener(executor, modeListener)
            }
            // Registered on every version: on API 30 it is the only trigger, and above it is a cheap
            // backstop in case a mode change is ever missed.
            audioManager.registerAudioPlaybackCallback(playbackTrigger, handler)
            audioManager.registerAudioRecordingCallback(recordingTrigger, handler)
            registered = true
            AppLogger.i(TAG, "VoIP call detection active")
            evaluate(audioManager.mode)   // a call may already be in progress
            // A normal-mode call already in progress too (C9): registering delivers no current state, so a steady
            // LINE call would otherwise wait for its next player or recorder change.
            watchNormalMode()
        }.onFailure { AppLogger.w(TAG, "Could not start VoIP detection: ${it.message}") }
    }

    /** Stops watching and ends any in-flight recording. Idempotent. */
    fun stop() {
        handler.removeCallbacks(debouncedEndRunnable)
        handler.removeCallbacks(tailRecheck)
        clearNormalCandidate()
        handler.removeCallbacks(normalEndRunnable)
        carrierEpisode = VoipStrayStartPolicy.Latch()
        if (callActive) endCallRunnable.run()
        handler.removeCallbacks(probeHeartbeat)
        heartbeatScheduled = false
        if (!registered || audioManager == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.removeOnModeChangedListener(modeListener)
            }
            audioManager.unregisterAudioPlaybackCallback(playbackTrigger)
            audioManager.unregisterAudioRecordingCallback(recordingTrigger)
        }
        registered = false
        AppLogger.i(TAG, "VoIP call detection stopped")
    }

    /** Re-reads the preference: starts watching when switched on, stops when switched off. */
    fun sync() {
        if (AppPreferences(context).isVoipRecordingEnabled()) start() else stop()
    }

    private companion object {
        const val TAG = "CV:VoipDetect"

        /** Looked at just after the carrier-episode tail, never on its edge. */
        const val TAIL_RECHECK_SLACK_MS = 50L

        /** #53 diagnostic: heartbeat while a call is up, and how long to keep logging after it ends. */
        const val PROBE_HEARTBEAT_MS = 5_000L
        const val PROBE_TAIL_MS = 60_000L

        /**
         * How long an app call may stay held for a carrier call before it is finalised anyway.
         *
         * Generous, because a genuinely long phone call taken in the middle of an app call is normal
         * and cutting it short would lose the second half. Bounded, because the held recording is
         * holding an encoder, a muxer and the output fd the entire time.
         */
        const val SUSPEND_MAX_MS = 45L * 60L * 1000L
    }
}
