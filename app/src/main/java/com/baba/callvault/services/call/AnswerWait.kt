/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

/**
 * "Start when they answer": the decision behind waiting for an outgoing call to be picked up.
 *
 * An outgoing call reads OFFHOOK from the moment it is dialled, so a recording started there holds
 * the dialling and ringing too. The precise state that tells DIALING and ALERTING from ACTIVE is
 * behind `READ_PRECISE_PHONE_STATE`, a signature permission the app cannot hold — but the recorder
 * host runs as the shell user and `dumpsys telephony.registry` prints it as `mForegroundCallState`,
 * so the host is polled through the fixed `call_state` dump and the recording starts at the first
 * ACTIVE.
 *
 * The rule throughout: **nothing here may cost the call.** No signal, a state this never expected, or
 * simply too long a wait all start the recording as if the option were off. The only answer that
 * keeps waiting is a state that says the far end has not picked up yet.
 */
object AnswerWait {

    /** `PreciseCallState` values, copied because the class is hidden from apps. */
    const val STATE_IDLE = 0
    const val STATE_ACTIVE = 1
    const val STATE_HOLDING = 2
    const val STATE_DIALING = 3
    const val STATE_ALERTING = 4

    /** How often the host is asked. Half a second is at most half a second of the answer lost. */
    const val POLL_MS = 500L

    /**
     * How long to wait before starting regardless. Carriers give up ringing well inside this, and a
     * voicemail that picks up is ACTIVE like anyone else; only a ROM whose registry never says
     * ACTIVE reaches it, and that ROM still gets its call recorded.
     */
    const val MAX_WAIT_MS = 120_000L

    /**
     * How long a host that is not connected yet is waited for. Standby asks for the daemon at the
     * dial and the binder follows a moment later; a few seconds covers a relaunch without turning
     * a dead host into a two-minute wait.
     */
    const val HOST_GRACE_MS = 5_000L

    enum class Decision { WAIT, START }

    private val STATE_LINE = Regex("""mForegroundCallState=(\d+)""")

    /**
     * The foreground call state in [dump], or null when there is none to read.
     *
     * The dump has one line per phone. On a dual-SIM device the SIM that is not in the call reports
     * IDLE the whole time, so the answer is the state of whichever phone is in a call: an answered
     * one first, then any that is busy at all, and IDLE only when every line says so.
     */
    fun parseState(dump: String?): Int? {
        val states = dump?.let { STATE_LINE.findAll(it) }
            ?.mapNotNull { it.groupValues[1].toIntOrNull() }
            ?.toList()
            .orEmpty()
        if (states.isEmpty()) return null
        return states.firstOrNull { it == STATE_ACTIVE || it == STATE_HOLDING }
            ?: states.firstOrNull { it != STATE_IDLE }
            ?: STATE_IDLE
    }

    /**
     * What to do on a poll that read [state] (null = nothing readable) [elapsedMs] into the wait.
     * [hostConnected] is false when there was no host to ask at all, which is waited out briefly.
     */
    fun decide(state: Int?, elapsedMs: Long, hostConnected: Boolean = true): Decision = when {
        state == null && !hostConnected && elapsedMs < HOST_GRACE_MS -> Decision.WAIT
        state == null -> Decision.START
        elapsedMs >= MAX_WAIT_MS -> Decision.START
        state == STATE_ACTIVE || state == STATE_HOLDING -> Decision.START
        else -> Decision.WAIT
    }
}
