/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.integrations.adb.WifiState

/**
 * The pure rules the keep-alive uses to decide whether a relaunch is worth trying, kept out of the service so they are
 * unit-tested. Every rule here only ever makes an attempt cheaper or skips one that cannot work; none slows the
 * once-a-minute rhythm of an attempt that can.
 */
object RewarmDecisions {

    /**
     * Whether the Wi-Fi state lets a relaunch run (review C34). Only a proven "no Wi-Fi" blocks the Wireless debugging
     * path, and only when off-Wi-Fi recording is off (the loopback path needs no Wi-Fi). An unreadable state is not
     * evidence, and a Wi-Fi without internet while mobile data is up is still Wi-Fi: [WifiState.of] looks at every
     * network, not the default one, which is what the old default-network check got wrong.
     */
    fun wifiAllowsRelaunch(offlineRecording: Boolean, wifi: WifiState): Boolean =
        offlineRecording || wifi != WifiState.NOT_CONNECTED

    /** What the keep-alive does about a down daemon, given the notice that describes why recovery may be blocked. */
    enum class RelaunchPlan {
        /** Nothing can be dialled: stand down, as before. */
        STAND_DOWN,

        /** Wireless debugging is off by the user's choice but adbd answers on an already-open tcp port: dial that, never touch the switch. */
        VIA_OPEN_PORT,

        /** The ordinary relaunch path. */
        NORMAL,
    }

    /**
     * Review C34 retest (OP9 rf26): `WD_OFF_BY_USER` used to stand the keep-alive down at once, although adbd was
     * listening on loopback :5555 and had worked a minute earlier. Before standing down, ask the open port; only
     * when it does not answer is recovery really impossible. [openPortReachable] is a network probe, so it is
     * evaluated only for the one notice that needs it. The plan never includes turning Wireless debugging on.
     */
    fun relaunchPlan(
        notice: ReadinessNotice,
        consecutiveOpenPortFailures: Int,
        openPortReachable: () -> Boolean,
    ): RelaunchPlan = when (notice) {
        ReadinessNotice.NEEDS_WIFI -> RelaunchPlan.STAND_DOWN
        // The cap comes first so an exhausted back-off never probes: a port that answers but whose launch keeps
        // failing would otherwise re-run the launch every minute for ever (and, if Android forgot our key, show
        // "Allow USB debugging?" every minute).
        ReadinessNotice.WD_OFF_BY_USER ->
            if (consecutiveOpenPortFailures >= MAX_OPEN_PORT_FAILURES) RelaunchPlan.STAND_DOWN
            // A probe that throws is "not reachable", never a dead keep-alive thread.
            else if (runCatching(openPortReachable).getOrDefault(false)) RelaunchPlan.VIA_OPEN_PORT
            else RelaunchPlan.STAND_DOWN
        else -> RelaunchPlan.NORMAL
    }

    /** Failed launches through the open port, in a row, before the keep-alive stands down as it did before this path existed. */
    const val MAX_OPEN_PORT_FAILURES = 3
}

/**
 * Held while a recording runs on the local ADB fallback (review C89): that recording rides the same ADB connection a
 * relaunch would drop or reconfigure, so the keep-alive must leave it alone until the call ends. A counter, so two
 * overlapping sessions release it only when both are done, and a stray extra [exit] cannot make it negative.
 *
 * A lease that is never released (a missed exit) must not block relaunches for ever: it counts as released
 * [maxHoldMs] after it was taken (or last renewed by another [enter]), and [expireIfStale] says so once so the caller
 * can write one always-on line.
 */
class LocalFallbackLease(
    private val maxHoldMs: Long = MAX_HOLD_MS,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var holders = 0
    private var heldSinceMs = 0L

    /** True while held and not past [maxHoldMs]. */
    @get:Synchronized
    val active: Boolean get() = holders > 0 && clock() - heldSinceMs < maxHoldMs

    @Synchronized
    fun enter() {
        holders++
        heldSinceMs = clock()
    }

    @Synchronized
    fun exit() {
        if (holders > 0) holders--
    }

    /** Drops a lease held longer than [maxHoldMs]. True only on the call that dropped it. */
    @Synchronized
    fun expireIfStale(): Boolean {
        if (holders == 0 || clock() - heldSinceMs < maxHoldMs) return false
        holders = 0
        return true
    }

    companion object {
        /** Longer than any call worth recording; a hold past this is a missed release, not a call. */
        const val MAX_HOLD_MS = 3 * 60 * 60_000L

        /** The process-wide lease the recording engine holds and the keep-alive reads. */
        val shared = LocalFallbackLease()
    }
}

/** The run of failed open-port launches [RewarmDecisions.relaunchPlan] caps. Thread-safe: the keep-alive thread and the switch observer both touch it. */
class OpenPortFailures {
    private val failures = java.util.concurrent.atomic.AtomicInteger(0)
    val count: Int get() = failures.get()
    fun recordFailure(): Int = failures.incrementAndGet()
    fun reset() = failures.set(0)
}
