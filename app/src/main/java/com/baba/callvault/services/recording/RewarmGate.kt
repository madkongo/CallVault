/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Decides whether a daemon relaunch may start now. Two rules, and one hard-won third.
 *
 *  - **One at a time.** Two concurrent launches disconnect and reconnect on each other mid-connect and
 *    thrash the embedded ADB connection into a "Stream closed" state.
 *  - **Throttled.** An un-forced (watchdog) attempt waits [throttleMs] after the last one, so a launch
 *    that keeps failing is not hammered. A confirmed binder death passes `force = true` and skips this
 *    — a genuine recovery must not sit out the throttle. Force does *not* skip the one-at-a-time rule.
 *  - **In-flight state expires.** This is the fix for the 2026-07-30 field wedge, and the reason this
 *    logic is a separate testable class rather than two fields on the service.
 *
 * The wedge: the in-flight flag used to be cleared *only* by the worker thread finishing. That worker
 * calls `ensureServerRunning`, which could block indefinitely on a half-dead ADB connection — so a
 * single hung attempt latched the flag true for the life of the process, the 60 s watchdog returned at
 * its first line forever, and recording stopped silently. Found after ~21 hours down on a device that
 * otherwise looked perfectly healthy. Treating an attempt older than [stuckAfterMs] as abandoned means
 * the worst case is one lost window, not a permanent outage.
 *
 * Thread-safe: [tryEnter] is called from the watchdog's handler thread while [leave] is called from the
 * relaunch worker it spawned, so the state genuinely crosses threads and both methods are synchronized.
 * Without that, the handler thread could keep observing a stale `inFlight = true` and wait out
 * [stuckAfterMs] before retrying a relaunch that had in fact already finished.
 *
 * @param stuckAfterMs age past which an in-flight attempt is presumed abandoned and may be superseded.
 *   Must comfortably exceed a healthy `ensureServerRunning`, or a slow-but-working launch gets a
 *   competitor.
 * @param throttleMs minimum gap between un-forced attempts.
 */
class RewarmGate(
    private val stuckAfterMs: Long,
    private val throttleMs: Long,
) {

    private var inFlight = false
    private var startedAtMs = 0L

    // An explicit "never attempted" flag rather than a sentinel timestamp: with Long.MIN_VALUE,
    // `now - lastAttemptAtMs` overflows to a negative value and the throttle silently blocks the
    // FIRST attempt — the daemon would then never warm until the throttle window happened to pass.
    private var hasAttempted = false
    private var lastAttemptAtMs = 0L

    /**
     * Claims the right to start a relaunch, returning false if the caller should stand down.
     *
     * @param now monotonic milliseconds (`SystemClock.elapsedRealtime`), never wall clock — a clock
     *   change must not open or close this gate.
     * @param force skip the throttle for a confirmed binder death.
     */
    @Synchronized
    fun tryEnter(now: Long, force: Boolean): Boolean {
        if (inFlight && now - startedAtMs < stuckAfterMs) return false
        if (!force && hasAttempted && now - lastAttemptAtMs < throttleMs) return false
        inFlight = true
        startedAtMs = now
        hasAttempted = true
        lastAttemptAtMs = now
        return true
    }

    /** Marks the in-flight attempt finished. Idempotent, so it is safe in a `finally`. */
    @Synchronized
    fun leave() {
        inFlight = false
    }

    /** Runs one claimed attempt and always leaves the gate, even when [block] throws (an exception must not park recovery until the gate expires). */
    fun <T> leavingAfter(block: () -> T): T = try { block() } finally { leave() }
}
