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

    // --- C130 / R8: stand-down ---

    private fun standDown() = RewarmStandDown(safetyNetMs = 10 * 60_000L)

    @Test
    fun `the two hard causes always stand down, even for a forced relaunch`() {
        val s = standDown()
        for (n in listOf(ReadinessNotice.WD_OFF_BY_USER, ReadinessNotice.NEEDS_WIFI)) {
            assertEquals(RewarmStandDown.Verdict.STAND_DOWN, s.check(n, force = false, now = 0L))
            assertEquals(RewarmStandDown.Verdict.STAND_DOWN, s.check(n, force = true, now = 0L))
        }
    }

    @Test
    fun `an ordinary notice never stands down`() {
        val s = standDown()
        for (n in listOf(ReadinessNotice.STARTING, ReadinessNotice.STUCK, ReadinessNotice.READY)) {
            assertEquals(RewarmStandDown.Verdict.PROCEED, s.check(n, force = false, now = 0L))
        }
    }

    @Test
    fun `lost pairing is tried once when it appears, then stands down`() {
        val s = standDown()
        assertEquals(RewarmStandDown.Verdict.PROCEED, s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 0L))
        assertEquals(RewarmStandDown.Verdict.STAND_DOWN, s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 60_000L))
        assertEquals(RewarmStandDown.Verdict.STAND_DOWN, s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 9 * 60_000L))
    }

    @Test
    fun `lost pairing gets one safety-net attempt every few minutes so a false alarm cannot stick`() {
        val s = standDown()
        s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 0L)
        assertEquals(RewarmStandDown.Verdict.PROCEED, s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 10 * 60_000L))
        assertEquals(RewarmStandDown.Verdict.STAND_DOWN, s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 11 * 60_000L))
    }

    @Test
    fun `a change of cause is acted on at once`() {
        val s = standDown()
        s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 0L)
        // The user re-paired: the notice moves on, and the very next minute tries.
        assertEquals(RewarmStandDown.Verdict.PROCEED, s.check(ReadinessNotice.STARTING, force = false, now = 60_000L))
        // And if the same cause comes back it is a new appearance, tried once again.
        assertEquals(RewarmStandDown.Verdict.PROCEED, s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 120_000L))
    }

    @Test
    fun `a forced relaunch after a confirmed binder death is never held back by a soft cause`() {
        val s = standDown()
        s.check(ReadinessNotice.PAIRING_LOST, force = false, now = 0L)
        assertEquals(RewarmStandDown.Verdict.PROCEED, s.check(ReadinessNotice.PAIRING_LOST, force = true, now = 1_000L))
    }

    @Test
    fun `no debugging and no grant stands down like lost pairing`() {
        val s = standDown()
        s.check(ReadinessNotice.NO_DEBUGGING, force = false, now = 0L)
        assertEquals(RewarmStandDown.Verdict.STAND_DOWN, s.check(ReadinessNotice.NO_DEBUGGING, force = false, now = 60_000L))
    }

    // --- C89: a recording on the local fallback owns the ADB connection ---

    @Test
    fun `the lease is held while a local-fallback recording runs and released after`() {
        val lease = LocalFallbackLease()
        assertFalse(lease.active)
        lease.enter()
        assertTrue(lease.active)
        lease.exit()
        assertFalse(lease.active)
    }

    @Test
    fun `two overlapping local recordings keep the lease until both end`() {
        val lease = LocalFallbackLease()
        lease.enter(); lease.enter()
        lease.exit()
        assertTrue(lease.active)
        lease.exit()
        assertFalse(lease.active)
    }

    @Test
    fun `an extra exit never makes the lease negative`() {
        val lease = LocalFallbackLease()
        lease.exit()
        lease.enter()
        assertTrue(lease.active)
    }
}
