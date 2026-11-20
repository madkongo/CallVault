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
