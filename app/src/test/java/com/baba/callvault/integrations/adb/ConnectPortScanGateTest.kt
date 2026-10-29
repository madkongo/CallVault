/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #65: a loopback search that found nothing is not repeated in the same Wireless-debugging session.
 * The report retried a connect every few seconds for 25 minutes; without this each retry would search.
 */
class ConnectPortScanGateTest {

    private val gate = ConnectPortScanGate(retryAfterMs = 60_000)

    @Test
    fun `a first search is allowed`() {
        assertTrue(gate.mayScan("wifi-1", 0))
    }

    @Test
    fun `after a failed search the same session does not search again`() {
        gate.recordFailure("wifi-1", 0)
        assertFalse(gate.mayScan("wifi-1", 1_000))
        assertFalse(gate.mayScan("wifi-1", 59_999))
    }

    @Test
    fun `a Wireless-debugging change lets it search again`() {
        gate.recordFailure("wifi-1", 0)
        gate.reset()
        assertTrue(gate.mayScan("wifi-1", 1_000))
    }

    @Test
    fun `another Wi-Fi network is another session`() {
        gate.recordFailure("wifi-1", 0)
        assertTrue(gate.mayScan("wifi-2", 1_000))
        // And the old failure is gone with it.
        assertTrue(gate.mayScan("wifi-1", 1_001))
    }

    @Test
    fun `the failure expires as a backstop for a missed switch change`() {
        gate.recordFailure("wifi-1", 0)
        assertTrue(gate.mayScan("wifi-1", 60_000))
    }

    @Test
    fun `the skip is logged once per failure`() {
        gate.recordFailure("wifi-1", 0)
        assertTrue(gate.shouldLogSkip())
        assertFalse(gate.shouldLogSkip())
        gate.recordFailure("wifi-1", 10)
        assertTrue(gate.shouldLogSkip())
    }
}
