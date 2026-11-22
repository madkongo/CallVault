/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * The daemon's answer to the app process dying while it records (2026-11-22).
 *
 * The start-of-process hand-over rides on the activity manager's "uid became active" event. That event does not fire
 * when the app's uid never went idle: on the OnePlus 9 the `:drive` process stayed alive, the main process was killed
 * and restarted for a service, and no hand-over ever came, so the restarted app killed the recording daemon. The
 * daemon therefore also watches the app's provider binder and, when it dies while recording, delivers again (a
 * delivery starts the app's process itself). Only while recording: an idle daemon must not restart an app the user
 * stopped.
 */
object AppDeathRedelivery {

    private val FIRST_DELAYS_MS = longArrayOf(1_000L, 2_000L, 2_000L)

    /** After the quick retries, a still-unclaimed recording is offered the binder again at this gap. */
    const val PERIODIC_GAP_MS = 15_000L

    /** Total attempts per death (3 quick, then periodic): about two minutes, then the daemon stops asking. */
    const val MAX_ATTEMPTS = 9

    /** App deaths per recording session that may each restart the app; beyond it an OEM killer / crash loop is left alone. */
    const val MAX_CYCLES = 3

    /** Two deaths closer than this count as one loop, not as a new cycle. */
    const val MIN_CYCLE_GAP_MS = 10_000L

    /**
     * Milliseconds to wait before attempt [attempt] (0-based), or null to stop. [clientSeen] is true once any app call
     * reached this daemon's binder since the death: the app holds it, whatever the provider's reply said ("delivered"
     * only means the provider replied; the app may have refused the binder).
     */
    fun nextDelayMs(attempt: Int, recording: Boolean, clientSeen: Boolean): Long? = when {
        !recording -> null
        clientSeen -> null
        attempt >= MAX_ATTEMPTS -> null
        attempt < FIRST_DELAYS_MS.size -> FIRST_DELAYS_MS[attempt]
        else -> PERIODIC_GAP_MS
    }

    /** Whether this death may start another re-delivery cycle (each one restarts the app's process). */
    fun mayStartCycle(cyclesSoFar: Int, sinceLastCycleMs: Long?): Boolean =
        cyclesSoFar < MAX_CYCLES && (sinceLastCycleMs == null || sinceLastCycleMs >= MIN_CYCLE_GAP_MS)

    /** The uid observer is subscribed to "active" only, but its transaction code must say so; unknown code = accept as before. */
    fun isActiveTransaction(code: Int, activeCode: Int?): Boolean = activeCode == null || code == activeCode

    /**
     * Whether to deliver to the app at all. A force-stopped app is left stopped: the user stopped it on purpose, and a
     * delivery would reopen it. An unreadable state is not "stopped" (the daemon cannot tell; it logs that).
     */
    fun mayDeliver(packageStopped: Boolean?): Boolean = packageStopped != true

    /** android.content.pm.ApplicationInfo.FLAG_STOPPED, the fallback when `isPackageStoppedForUser` is missing. */
    const val FLAG_STOPPED = 1 shl 21
}

/**
 * The daemon finishing a recording itself once the app that owned it is gone and the call is over, so the muxer
 * trailer is written and the file is playable (a SIGTERM would leave it unfinalised). Conservative: never while a call
 * audio mode is up, and never when the audio mode cannot be read.
 */
class OrphanSelfStop(private val quietMs: Long = QUIET_MS) {
    enum class Decision { IDLE, WAIT, UNREADABLE, STOP }

    private var noCallSince: Long? = null

    /** One poll. [clientAttached]: an app call reached this daemon since the app's death. [callUp] null = unreadable. */
    fun onPoll(nowMs: Long, recording: Boolean, clientAttached: Boolean, callUp: Boolean?): Decision {
        if (!recording || clientAttached) { noCallSince = null; return Decision.IDLE }
        if (callUp == null) { noCallSince = null; return Decision.UNREADABLE }
        if (callUp) { noCallSince = null; return Decision.WAIT }
        val since = noCallSince ?: nowMs.also { noCallSince = it }
        return if (nowMs - since >= quietMs) Decision.STOP else Decision.WAIT
    }

    companion object {
        /**
         * No call audio mode and no VOICE_COMMUNICATION playback for this long before the daemon stops the recording
         * itself. Longer than the app's own wait for a young VoIP call's audio mode to come back
         * (VoipRingDipPolicy.WAIT_FOR_RETURN_MS = 30 s; LINE runs in MODE_NORMAL), shorter than the cut-off rescue's 90 s.
         */
        const val QUIET_MS = 35_000L

        /** Whether a call is up: either signal says so; unreadable only when neither says so and one cannot be read. */
        fun callUp(modeIsCall: Boolean?, voiceCommActive: Boolean?): Boolean? = when {
            modeIsCall == true || voiceCommActive == true -> true
            modeIsCall == null || voiceCommActive == null -> null
            else -> false
        }

        /**
         * Any number of call signals: true if any says a call is up; null (unreadable) when none does and one cannot be
         * read, since an unreadable signal is never evidence of "no call"; false only when every one reads false.
         */
        fun callUpAny(vararg signals: Boolean?): Boolean? = when {
            signals.any { it == true } -> true
            signals.any { it == null } -> null
            else -> false
        }

        // AudioManager modes: RINGTONE, IN_CALL, IN_COMMUNICATION, CALL_SCREENING, CALL_REDIRECT, COMMUNICATION_REDIRECT.
        // Kept here (not read from the app's AfterCallPolicy) so the daemon loads nothing from the app's UI layer.
        fun isCallAudioMode(mode: Int): Boolean = mode in 1..6
    }
}
