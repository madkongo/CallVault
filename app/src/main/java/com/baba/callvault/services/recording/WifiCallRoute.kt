/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telephony.TelephonyManager
import com.baba.callvault.server.RecorderConnection

/**
 * Which capture a carrier call should use (#45).
 *
 * On a Samsung Wi-Fi call the VOICE_CALL source has no far side. The phone sits in
 * MODE_IN_COMMUNICATION and plays the far side as a USAGE_VOICE_COMMUNICATION player, which our
 * app-call capture (loopback mix + mic) can record. Upstream ShizuCallRecorder #136 recorded both
 * sides that way on a Galaxy S25 Ultra.
 *
 * The switch happens only when every condition holds. Anything else keeps today's capture, so an
 * ordinary modem call (IN_CALL, no voice-comm player) is never touched.
 */
object WifiCallRoute {

    /** Log tag for every route decision, switch and end line. */
    const val TAG = "CV:WifiRoute"

    /**
     * How long after the carrier capture starts a switch is still allowed. Samsung reaches
     * IN_COMMUNICATION ~350 ms after OFFHOOK; later, the mode is someone else's (a held call, a
     * WhatsApp call taken on top). It also bounds the audio a switch can discard.
     */
    const val SWITCH_WINDOW_MS = 5_000L

    enum class Route { CALL_SOURCE, APP_CALL_MIX }

    enum class Reason {
        WIFI_CALL,
        FORCED,
        ALREADY_SWITCHED,
        NOT_MANAGED,
        WINDOW_PASSED,
        MODE_NOT_IN_COMMUNICATION,
        NO_VOICE_COMM_PLAYER,
        APP_CALL_HELD,
        APP_CALLS_OFF,
        DAEMON_DOWN,
        /** Everything else said switch; only Samsung is known to need it. Logged, never acted on. */
        NOT_SAMSUNG,
    }

    data class Decision(val route: Route, val reason: Reason)

    data class Inputs(
        val mode: Int,
        /** Telecom has a managed (carrier) call up. */
        val managedCall: Boolean,
        /** Active players with usage VOICE_COMMUNICATION. */
        val voiceCallPlayers: Int,
        /** App-call recording is on, so its capture policy is armed. */
        val voipArmed: Boolean,
        val daemonConnected: Boolean,
        /** One-way latch: once switched, never again (and never back). */
        val alreadySwitched: Boolean,
        /** An app call is held open behind this phone call; the daemon is busy with it. */
        val appCallHeld: Boolean,
        /** Debug switch: skip the mode, player and maker checks. */
        val forced: Boolean,
        /** Time since the carrier capture started; 0 at START. */
        val msSinceCaptureStart: Long,
        val isSamsung: Boolean,
    )

    fun decide(i: Inputs): Decision {
        fun stay(reason: Reason) = Decision(Route.CALL_SOURCE, reason)
        if (i.alreadySwitched) return stay(Reason.ALREADY_SWITCHED)
        if (!i.managedCall) return stay(Reason.NOT_MANAGED)
        if (i.msSinceCaptureStart > SWITCH_WINDOW_MS) return stay(Reason.WINDOW_PASSED)
        if (!i.forced) {
            if (i.mode != AudioManager.MODE_IN_COMMUNICATION) return stay(Reason.MODE_NOT_IN_COMMUNICATION)
            if (i.voiceCallPlayers < 1) return stay(Reason.NO_VOICE_COMM_PLAYER)
        }
        if (i.appCallHeld) return stay(Reason.APP_CALL_HELD)
        if (!i.voipArmed) return stay(Reason.APP_CALLS_OFF)
        if (!i.daemonConnected) return stay(Reason.DAEMON_DOWN)
        if (!i.forced && !i.isSamsung) return stay(Reason.NOT_SAMSUNG)
        return Decision(Route.APP_CALL_MIX, if (i.forced) Reason.FORCED else Reason.WIFI_CALL)
    }

    /** A decision and what it was made from, as logged. */
    data class Observation(val decision: Decision, val seen: String)

    fun isWorthLogging(previous: Observation?, current: Observation): Boolean = previous != current

    /** Reads the live inputs; the caller supplies its own state and settings. */
    fun snapshot(
        context: Context,
        alreadySwitched: Boolean,
        forced: Boolean,
        voipEnabled: Boolean,
        msSinceCaptureStart: Long,
    ): Pair<Inputs, String> {
        val am = context.getSystemService(AudioManager::class.java)
        val probe = am?.let { runCatching { VoipEndProbe.capture(it) }.getOrNull() }
        val inputs = Inputs(
            mode = probe?.mode ?: AudioManager.MODE_NORMAL,
            managedCall = VoipTelephonyGate.managedCallInProgress(context),
            voiceCallPlayers = probe?.voiceCallPlayers ?: 0,
            voipArmed = voipEnabled,
            daemonConnected = RecorderConnection.isConnected,
            alreadySwitched = alreadySwitched,
            appCallHeld = VoipRecordingCoordinator.isRecording,
            forced = forced,
            msSinceCaptureStart = msSinceCaptureStart,
            isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        )
        val seen = (probe?.let { VoipEndProbe.describe(it) } ?: "mode=unknown") +
            " managed=${inputs.managedCall} voiceNet=${voiceNetworkName(context)}"
        return inputs to seen
    }

    /** Log only: IWLAN is the hint that the call runs over Wi-Fi. Never a decision input. */
    @SuppressLint("MissingPermission") // READ_PHONE_STATE is held; a refusal is caught below.
    private fun voiceNetworkName(context: Context): String = runCatching {
        when (val type = context.getSystemService(TelephonyManager::class.java)?.voiceNetworkType) {
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "NR"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
            null -> "n/a"
            else -> "type$type"
        }
    }.getOrElse { "unreadable" }
}
