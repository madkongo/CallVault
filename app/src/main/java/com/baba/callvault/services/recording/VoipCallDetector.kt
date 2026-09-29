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
import android.os.Build
import android.os.Handler
import android.os.Looper
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


    /** Set while a recording is running, so the host can reflect it in its notification. */
    @Volatile
    var isRecording: Boolean = false
        private set

    /** Notified when [isRecording] changes, so the keep-alive notification can show the state. */
    var onRecordingStateChanged: (() -> Unit)? = null

    private val modeListener = AudioManager.OnModeChangedListener { mode -> evaluate(mode) }

    /**
     * Secondary trigger for API 30, where [AudioManager.OnModeChangedListener] does not exist. The
     * configurations themselves are ignored (they are anonymised for us) — this only says "something
     * about playback changed, go re-read the mode".
     */
    private val playbackTrigger = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            evaluate(audioManager?.mode ?: AudioManager.MODE_NORMAL)
        }
    }

    /** A call's mode can wobble briefly on route changes, so ending is debounced. */
    private val endCallRunnable = Runnable {
        if (callActive) {
            callActive = false
            suspendedForCarrier = false
            handler.removeCallbacks(suspendTimeout)
            AppLogger.i(TAG, "VoIP call ended")
            // Take the prompt down with the call. Leaving it would offer to record a call that is
            // over, and tapping it would start a recording of nothing.
            VoipRecordPrompt.cancel(context)
            runCatching { VoipRecordingCoordinator.onCallEnded(context) }
                .onFailure { AppLogger.w(TAG, "VoIP stop failed: ${it.message}") }
            CutOffRescueWorker.schedule(context)
            isRecording = false
            onRecordingStateChanged?.invoke()
        }
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
        if (!callActive || isRecording) {
            AppLogger.i(TAG, "Manual app-call start ignored (callActive=$callActive recording=$isRecording)")
            VoipRecordPrompt.cancel(context)
            return
        }
        AppLogger.i(TAG, "Recording this app call on the user's request")
        VoipRecordPrompt.cancel(context)
        runCatching { VoipRecordingCoordinator.onCallStarted(context) }
            .onFailure { AppLogger.w(TAG, "Manual VoIP start failed: ${it.message}") }
        isRecording = VoipRecordingCoordinator.isRecording
        onRecordingStateChanged?.invoke()
    }

    /**
     * Drops an app-call recording because a carrier call has been answered.
     *
     * The mode listener cannot be relied on for this: on a ROM where an IMS carrier call reads as
     * MODE_IN_COMMUNICATION, the mode never changes when the phone call starts, so nothing would fire
     * and the capture would run on through it. The telephony broadcast is the signal that does arrive.
     */
    fun abortForCarrierCall() {
        if (!callActive) return
        handler.removeCallbacks(endCallRunnable)

        // Hold it open rather than ending it, so returning to the app call continues the SAME file.
        // Splitting one conversation across two recordings is what this replaces.
        val held = runCatching { VoipRecordingCoordinator.setSuspendedForCarrierCall(context, true) }
            .getOrDefault(false)
        if (held) {
            suspendedForCarrier = true
            handler.removeCallbacks(suspendTimeout)
            handler.postDelayed(suspendTimeout, SUSPEND_MAX_MS)
            AppLogger.i(TAG, "A carrier call was answered — holding the app-call recording open")
            // callActive stays true and the prompt stays cancelled: the app call is still up, it is
            // simply not the one the phone is playing.
            VoipRecordPrompt.cancel(context)
            return
        }

        // The daemon could not hold it — an older one has no setVoipSuspended — so fall back to what
        // this always did. Two files is worse than one, and far better than a file nothing is
        // writing to and nobody will ever finalise.
        AppLogger.i(TAG, "A carrier call was answered — dropping the app-call recording (no suspend available)")
        callActive = false
        suspendedForCarrier = false
        VoipRecordPrompt.cancel(context)
        runCatching { VoipRecordingCoordinator.onCallEnded(context) }
            .onFailure { AppLogger.w(TAG, "VoIP stop failed: ${it.message}") }
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
        when (VoipSwitchPolicy.decide(mode, callActive, suspendedForCarrier)) {
            VoipSwitchPolicy.Action.NOTHING -> return

            // A phone call is still up and an app call is held behind it. Waiting is the whole point.
            VoipSwitchPolicy.Action.HOLD -> return

            VoipSwitchPolicy.Action.RESUME -> {
                handler.removeCallbacks(suspendTimeout)
                handler.removeCallbacks(endCallRunnable)
                suspendedForCarrier = false
                runCatching { VoipRecordingCoordinator.setSuspendedForCarrierCall(context, false) }
                    .onFailure { AppLogger.w(TAG, "VoIP resume failed: ${it.message}") }
                isRecording = VoipRecordingCoordinator.isRecording
                onRecordingStateChanged?.invoke()
                return
            }

            VoipSwitchPolicy.Action.END -> {
                handler.removeCallbacks(endCallRunnable)
                handler.postDelayed(endCallRunnable, END_DEBOUNCE_MS)
                return
            }

            VoipSwitchPolicy.Action.START -> Unit   // handled below
        }
        run {
            // The mode alone does not prove this is an app call. A carrier call carried over IMS
            // (Wi-Fi calling, some VoLTE stacks) can present as MODE_IN_COMMUNICATION too, and acting
            // on it would record a phone call the carrier path is already recording — see
            // [VoipTelephonyGate]. Deliberately NOT marking callActive: we believe no app call is up,
            // so a later mode event is free to reconsider once the phone call is over.
            if (!VoipTelephonyGate.mayStartNow(context)) {
                AppLogger.i(TAG, "mode=IN_COMMUNICATION but the carrier has a call up — not an app call")
                return
            }
            handler.removeCallbacks(endCallRunnable)
            callActive = true
            AppLogger.i(TAG, "VoIP call detected (mode=IN_COMMUNICATION)")

            // "Ask me": arm, but let the user decide. The detector still runs — it has to, or there
            // would be nothing to prompt about — and only the automatic start is withheld.
            val prefs = AppPreferences(context)
            val action = RecordingPolicy.forVoipCall(
                voipEnabled = prefs.isVoipRecordingEnabled(),
                autoStart = prefs.isVoipAutoStartEnabled(),
            )
            if (action == RecordingPolicy.VoipAction.OFFER) {
                AppLogger.i(TAG, "App-call auto-start is off — offering to record instead")
                VoipRecordPrompt.show(context, VoipRecordingCoordinator.currentCallAppLabel(context))
                onRecordingStateChanged?.invoke()
                return
            }
            runCatching { VoipRecordingCoordinator.onCallStarted(context) }
                .onFailure { AppLogger.w(TAG, "VoIP start failed: ${it.message}") }
            isRecording = VoipRecordingCoordinator.isRecording
            onRecordingStateChanged?.invoke()
        }
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
            registered = true
            AppLogger.i(TAG, "VoIP call detection active")
            evaluate(audioManager.mode)   // a call may already be in progress
        }.onFailure { AppLogger.w(TAG, "Could not start VoIP detection: ${it.message}") }
    }

    /** Stops watching and ends any in-flight recording. Idempotent. */
    fun stop() {
        handler.removeCallbacks(endCallRunnable)
        if (callActive) endCallRunnable.run()
        if (!registered || audioManager == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.removeOnModeChangedListener(modeListener)
            }
            audioManager.unregisterAudioPlaybackCallback(playbackTrigger)
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
        const val END_DEBOUNCE_MS = 1_500L

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
