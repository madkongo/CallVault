/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * When the keep-alive tick raises, and takes down, the "Calls are not being recorded" alert (review R1).
 *
 * Until R1 the only loud warning was posted at boot and when Shizuku died; any other lasting outage only reworded
 * the silent permanent notice, and the user learned of it from a lost call.
 *
 * One alert per outage, never a repeat: it is raised once the recorder has been down for [GRACE_MS] AND at least
 * [MIN_DOWN_TICKS] consecutive ticks, and not again until a live tick ends the outage. Both conditions are needed
 * because the clock alone is fooled by Doze: the handler sleeps, the first tick after waking sees a long-ago "down
 * since", and a recorder that is mid-relaunch would alert at once. A normal relaunch (the 1-minute rhythm, a screen-off
 * kill) comes back within a tick or two and an alive tick resets everything, so it never alerts.
 *
 * Nothing is raised while the user has switched recording off: the recorder is meant to be down.
 *
 * Pure so the rule is proved at a desk; the service owns the clock, the preferences and the notification.
 */
object RecorderDownAlertPolicy {

    /**
     * Three minutes of continuous downtime. The relaunch is tried on the second down read (about a minute in) and a
     * launch is bounded at ~24 s, so by three minutes at least one full relaunch has failed: the recorder is not
     * merely starting. Longer would leave minutes of calls unrecorded before the user hears of it.
     */
    const val GRACE_MS = 3 * 60_000L

    /** At one tick a minute, three reads in a row. */
    const val MIN_DOWN_TICKS = 3

    data class State(
        val downSinceMs: Long? = null,
        val downTicks: Int = 0,
        val alerted: Boolean = false,
    )

    enum class Action { NONE, POST, CLEAR }

    data class Step(val state: State, val action: Action)

    fun step(state: State, alive: Boolean, recordingSwitchedOff: Boolean, nowMs: Long): Step {
        if (alive || recordingSwitchedOff) {
            return Step(State(), if (state.alerted) Action.CLEAR else Action.NONE)
        }
        val since = state.downSinceMs ?: nowMs
        val ticks = state.downTicks + 1
        val due = !state.alerted && ticks >= MIN_DOWN_TICKS && nowMs - since >= GRACE_MS
        return Step(
            State(downSinceMs = since, downTicks = ticks, alerted = state.alerted || due),
            if (due) Action.POST else Action.NONE,
        )
    }
}
