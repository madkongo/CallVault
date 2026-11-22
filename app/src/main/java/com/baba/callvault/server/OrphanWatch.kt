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
 * What the daemon knows about the app since each of its deaths, kept in two kinds so an app that merely talks to the
 * daemon is not mistaken for one that owns the recording (2026-11-22, OnePlus 9: the restarted app only queried, and the
 * daemon's self-stop never ran).
 *  - holds: ANY transaction, the app has the binder (the re-delivery can stop asking);
 *  - owns: startRecording / stopRecording / a handoff or VoIP start or stop, the app claimed or ended the session (this
 *    alone disables the self-stop, since an app that owns it will end it).
 */
class ClientSignals {
    @Volatile private var lastHoldAt = Long.MIN_VALUE
    @Volatile private var lastOwnAt = Long.MIN_VALUE

    fun noteHold(now: Long) { lastHoldAt = now }

    /** An ownership call is also a hold. */
    fun noteOwner(now: Long) { lastOwnAt = now; lastHoldAt = now }

    fun holdsSince(diedAt: Long): Boolean = lastHoldAt >= diedAt
    fun ownsSince(diedAt: Long): Boolean = lastOwnAt >= diedAt
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
        clientHolds: Boolean,
        clientOwns: Boolean,
        callUp: Boolean?,
        voiceCommActive: Boolean?,
        packageStopped: () -> Boolean?,
    ): Step {
        if (!current) return Step(Action.SUPERSEDED)
        val decision = selfStop.onPoll(now, recording, clientOwns, OrphanSelfStop.callUp(callUp, voiceCommActive))
        if (clientOwns) return Step(Action.APP_RETURNED)
        if (clientHolds) deliveryDue = null // the app has the binder: no more offers, but the self-stop still runs
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
