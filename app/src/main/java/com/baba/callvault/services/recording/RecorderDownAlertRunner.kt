/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Drives [RecorderDownAlertPolicy] for the keep-alive service (review R1): reads the clock and the master switch,
 * dispatches POST and CLEAR, and writes each alert shown or cleared, with its cause, to the always-on log.
 *
 * Every Android dependency is passed in so the wiring is tested at a desk. Not thread-safe: the service calls it
 * from its watchdog thread only.
 */
class RecorderDownAlertRunner(
    private val isSwitchedOff: () -> Boolean,
    private val nowMs: () -> Long,
    /** Names the cause and its reason sentence; asked only when an alert is about to be posted. */
    private val describeCause: () -> Cause,
    /** Posts the alert with the reason sentence; returns whether it was actually shown. */
    private val post: (reason: String) -> Boolean,
    private val clear: () -> Unit,
    private val alwaysOnLog: (String) -> Unit,
) {
    /** [name] goes to the log; [reason] is the localized sentence the user reads. */
    data class Cause(val name: String, val reason: String, val shizukuMode: Boolean)

    var state = RecorderDownAlertPolicy.State()
        private set

    /** One keep-alive tick. [alive] is whether the recorder answered. */
    fun onTick(alive: Boolean) {
        val now = nowMs()
        val switchedOff = isSwitchedOff()
        val step = RecorderDownAlertPolicy.step(state, alive, switchedOff, now)
        state = step.state
        when (step.action) {
            RecorderDownAlertPolicy.Action.NONE -> Unit
            RecorderDownAlertPolicy.Action.POST -> {
                val cause = describeCause()
                val shown = post(cause.reason)
                val downForS = (now - (step.state.downSinceMs ?: now)) / 1000
                alwaysOnLog(
                    "recorder-down alert SHOWN cause=${cause.name} shizukuMode=${cause.shizukuMode} " +
                        "downFor=${downForS}s ticks=${step.state.downTicks} posted=$shown",
                )
            }
            RecorderDownAlertPolicy.Action.CLEAR -> {
                clear()
                val why = if (switchedOff && !alive) "recording switched off" else "recorder ready again"
                alwaysOnLog("recorder-down alert CLEARED reason=$why")
            }
        }
    }

    /**
     * The keep-alive is ending (destroyed): its alert has no one left to clear it,
     * so take it down now. A no-op when none is up.
     */
    fun onServiceStopping(why: String) {
        val wasAlerted = state.alerted
        state = RecorderDownAlertPolicy.State()
        if (!wasAlerted) return
        clear()
        alwaysOnLog("recorder-down alert CLEARED reason=keep-alive stopping ($why)")
    }
}
