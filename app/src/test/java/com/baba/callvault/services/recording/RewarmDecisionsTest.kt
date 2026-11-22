/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.integrations.adb.WifiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules the keep-alive's relaunch uses to decide whether to try (review C34, C130, C89, R8). */
class RewarmDecisionsTest {

    // --- C34 retest: WD off by the user, adbd still listening on an open tcp port ---

    @Test
    fun `wd off by user with the open port answering relaunches through the port`() {
        assertEquals(
            RewarmDecisions.RelaunchPlan.VIA_OPEN_PORT,
            RewarmDecisions.relaunchPlan(ReadinessNotice.WD_OFF_BY_USER) { true },
        )
    }

    @Test
    fun `wd off by user with the port closed stands down as before`() {
        assertEquals(
            RewarmDecisions.RelaunchPlan.STAND_DOWN,
            RewarmDecisions.relaunchPlan(ReadinessNotice.WD_OFF_BY_USER) { false },
        )
    }

    @Test
    fun `needs wifi stands down without probing the port`() {
        var probed = false
        val plan = RewarmDecisions.relaunchPlan(ReadinessNotice.NEEDS_WIFI) { probed = true; true }
        assertEquals(RewarmDecisions.RelaunchPlan.STAND_DOWN, plan)
        assertFalse(probed)
    }

    @Test
    fun `other notices take the normal path without probing the port`() {
        var probed = false
        val plan = RewarmDecisions.relaunchPlan(ReadinessNotice.STARTING) { probed = true; true }
        assertEquals(RewarmDecisions.RelaunchPlan.NORMAL, plan)
        assertFalse(probed)
    }

    // --- C34: Wi-Fi without internet is still Wi-Fi ---

    @Test
    fun `a Wi-Fi that is connected allows a relaunch`() {
        assertTrue(RewarmDecisions.wifiAllowsRelaunch(offlineRecording = false, wifi = WifiState.CONNECTED))
    }

    @Test
    fun `an unreadable Wi-Fi state is not evidence of no Wi-Fi so the relaunch is tried`() {
        assertTrue(RewarmDecisions.wifiAllowsRelaunch(offlineRecording = false, wifi = WifiState.UNKNOWN))
    }

    @Test
    fun `no Wi-Fi at all blocks the Wireless debugging relaunch`() {
        assertFalse(RewarmDecisions.wifiAllowsRelaunch(offlineRecording = false, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `off-Wi-Fi recording lets the loopback relaunch run without Wi-Fi`() {
        assertTrue(RewarmDecisions.wifiAllowsRelaunch(offlineRecording = true, wifi = WifiState.NOT_CONNECTED))
    }

    // --- C89: a recording on the local fallback owns the ADB connection ---

    private class Clock(var now: Long = 0L)

    private fun lease(c: Clock) = LocalFallbackLease(maxHoldMs = 1_000L, clock = { c.now })

    @Test
    fun `the lease is held while a local-fallback recording runs and released after`() {
        val lease = lease(Clock())
        assertFalse(lease.active)
        lease.enter()
        assertTrue(lease.active)
        lease.exit()
        assertFalse(lease.active)
    }

    @Test
    fun `two overlapping local recordings keep the lease until both end`() {
        val lease = lease(Clock())
        lease.enter(); lease.enter()
        lease.exit()
        assertTrue(lease.active)
        lease.exit()
        assertFalse(lease.active)
    }

    @Test
    fun `an extra exit never makes the lease negative`() {
        val lease = lease(Clock())
        lease.exit()
        lease.enter()
        assertTrue(lease.active)
    }

    @Test
    fun `a lease that is never released stops blocking after its limit`() {
        val c = Clock()
        val lease = lease(c)
        lease.enter()
        c.now = 999L
        assertTrue(lease.active)
        c.now = 1_000L
        assertFalse(lease.active)
    }

    @Test
    fun `a stale lease is reported dropped exactly once`() {
        val c = Clock()
        val lease = lease(c)
        lease.enter()
        assertFalse(lease.expireIfStale())
        c.now = 5_000L
        assertTrue(lease.expireIfStale())
        assertFalse(lease.expireIfStale())
        lease.enter()
        assertTrue(lease.active)
    }
}
