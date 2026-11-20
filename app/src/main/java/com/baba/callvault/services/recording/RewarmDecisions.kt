/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.integrations.adb.WifiState
import java.util.concurrent.atomic.AtomicInteger

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
 * Decides, once a minute, whether the keep-alive should spend an attempt on a recorder that is down, from the named
 * cause the notice already computes (reviews C130 and R8).
 *
 *  - **Hard causes** ([ReadinessNotice.WD_OFF_BY_USER], [ReadinessNotice.NEEDS_WIFI]): the user's own switch, or no
 *    Wi-Fi at all. Nothing to try; the switch observers and the next tick pick up the change. This is what the
 *    keep-alive always did.
 *  - **Soft causes** ([ReadinessNotice.PAIRING_LOST], [ReadinessNotice.NO_DEBUGGING]): nothing CallVault can do
 *    changes by trying again, but a wrong reading must never stick (lost pairing needs three refusals in a row and
 *    only a success clears it, so a false alarm could otherwise never clear itself). So a soft cause is tried once
 *    when it appears, once more every [safetyNetMs], and at once whenever the cause changes. A forced relaunch (a
 *    confirmed binder death: the recorder was alive a moment ago) is never held back by a soft cause.
 *  - Anything else proceeds as before.
 */
class RewarmStandDown(private val safetyNetMs: Long) {

    enum class Verdict { PROCEED, STAND_DOWN }

    private var lastSoft: ReadinessNotice? = null
    private var lastAttemptAtMs = 0L

    @Synchronized
    fun check(notice: ReadinessNotice, force: Boolean, now: Long): Verdict = when (notice) {
        ReadinessNotice.WD_OFF_BY_USER, ReadinessNotice.NEEDS_WIFI -> Verdict.STAND_DOWN
        ReadinessNotice.PAIRING_LOST, ReadinessNotice.NO_DEBUGGING -> when {
            force -> Verdict.PROCEED
            notice != lastSoft -> attempt(notice, now)
            now - lastAttemptAtMs >= safetyNetMs -> attempt(notice, now)
            else -> Verdict.STAND_DOWN
        }
        else -> {
            lastSoft = null
            Verdict.PROCEED
        }
    }

    private fun attempt(notice: ReadinessNotice, now: Long): Verdict {
        lastSoft = notice
        lastAttemptAtMs = now
        return Verdict.PROCEED
    }

    companion object {
        /** How often a soft cause still gets an attempt. */
        const val SAFETY_NET_MS = 10 * 60_000L
    }
}

/**
 * Held while a recording runs on the local ADB fallback (review C89): that recording rides the same ADB connection a
 * relaunch would drop or reconfigure, so the keep-alive must leave it alone until the call ends. A counter, so two
 * overlapping sessions release it only when both are done, and a stray extra [exit] cannot make it negative.
 */
class LocalFallbackLease {
    private val holders = AtomicInteger(0)

    val active: Boolean get() = holders.get() > 0

    fun enter() {
        holders.incrementAndGet()
    }

    fun exit() {
        holders.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    companion object {
        /** The process-wide lease the recording engine holds and the keep-alive reads. */
        val shared = LocalFallbackLease()
    }
}
