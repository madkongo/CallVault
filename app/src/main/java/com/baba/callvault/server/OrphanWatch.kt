/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/** App restarts by re-delivery, counted per recording session (a new session resets it), safe across threads. */
class RestartCycles {
    private var cycles = 0
    private var lastAt: Long? = null

    @Synchronized fun onSessionStarted() { cycles = 0; lastAt = null }

    /** Counts a restart when [AppDeathRedelivery.mayStartCycle] allows one. */
    @Synchronized fun tryStart(now: Long): Boolean {
        val ok = AppDeathRedelivery.mayStartCycle(cycles, lastAt?.let { now - it })
        if (ok) { cycles++; lastAt = now }
        return ok
    }

    @Synchronized fun count(): Int = cycles
}

/**
 * One tick of the daemon's watch over a recording whose app died, as a pure step so the order of the checks can be
 * tested: a newer death supersedes this watch; an app that is calling the daemon again, or a recording that ended,
 * finishes it (before any self-stop); then the self-stop; then the re-delivery, which a force-stopped app never gets.
 */
class OrphanWatch(
    private val selfStop: OrphanSelfStop = OrphanSelfStop(),
    mayRestart: Boolean,
    diedAt: Long,
) {
    enum class Action { SUPERSEDED, APP_RETURNED, RECORDING_ENDED, SELF_STOP, DELIVER, NONE }

    class Step(val action: Action, val audioUnreadable: Boolean = false, val appForceStopped: Boolean = false, val stoppedUnknown: Boolean = false)

    private var attempt = 0
    private var unknownDelivered = false
    private var deliveryDue: Long? = if (mayRestart) diedAt + (AppDeathRedelivery.nextDelayMs(0, true, false) ?: 0L) else null

    val attempts: Int get() = attempt

    /**
     * [callUp] / [voiceCommActive] are the audio mode and VOICE_COMMUNICATION playback reads (null = unreadable);
     * [packageStopped] is only asked when a delivery is due.
     */
    fun step(
        now: Long,
        current: Boolean,
        recording: Boolean,
        clientSeen: Boolean,
        callUp: Boolean?,
        voiceCommActive: Boolean?,
        packageStopped: () -> Boolean?,
    ): Step {
        if (!current) return Step(Action.SUPERSEDED)
        val decision = selfStop.onPoll(now, recording, clientSeen, OrphanSelfStop.callUp(callUp, voiceCommActive))
        if (clientSeen) return Step(Action.APP_RETURNED)
        if (!recording) return Step(Action.RECORDING_ENDED)
        if (decision == OrphanSelfStop.Decision.STOP) return Step(Action.SELF_STOP)
        val unreadable = decision == OrphanSelfStop.Decision.UNREADABLE
        val due = deliveryDue
        if (due != null && now >= due) {
            val stopped = packageStopped()
            when {
                !AppDeathRedelivery.mayDeliver(stopped) -> { deliveryDue = null; return Step(Action.NONE, unreadable, appForceStopped = true) }
                stopped == null && unknownDelivered -> { deliveryDue = null; return Step(Action.NONE, unreadable, stoppedUnknown = true) }
                else -> {
                    if (stopped == null) unknownDelivered = true
                    return Step(Action.DELIVER, unreadable, stoppedUnknown = stopped == null)
                }
            }
        }
        return Step(Action.NONE, unreadable)
    }

    /** The caller delivered (the provider replied or not) at [now]; schedules the next offer, or none. */
    fun onDelivered(now: Long) {
        attempt++
        deliveryDue = AppDeathRedelivery.nextDelayMs(attempt, true, false)?.let { now + it }
    }

    val deliveryExhausted: Boolean get() = deliveryDue == null
}
