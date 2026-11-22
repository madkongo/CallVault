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

/** R16: a flapping Wi-Fi must not start an approval every few seconds; a join is delayed, never dropped. */
class WifiApprovalSpacingTest {

    private val settle = 4_000L
    private val spacing = WifiApprovalSpacing.MIN_SPACING_MS

    @Test
    fun `the first join waits only the settle time`() {
        assertEquals(settle, WifiApprovalSpacing.delayMs(nowMs = 100_000, lastStartedAtMs = null, settleMs = settle))
    }

    @Test
    fun `a join long after the last approval waits only the settle time`() {
        assertEquals(settle, WifiApprovalSpacing.delayMs(nowMs = 100_000, lastStartedAtMs = 100_000 - spacing - 1, settleMs = settle))
    }

    @Test
    fun `a join right after an approval is pushed to thirty seconds after it`() {
        assertEquals(spacing - 1_000, WifiApprovalSpacing.delayMs(nowMs = 101_000, lastStartedAtMs = 100_000, settleMs = settle))
    }

    @Test
    fun `a join that is delayed is still run, never skipped`() {
        assertEquals(true, WifiApprovalSpacing.delayMs(nowMs = 100_001, lastStartedAtMs = 100_000, settleMs = settle) > 0)
    }

    @Test
    fun `a clock that went backwards does not hold the approval for ever`() {
        assertEquals(settle, WifiApprovalSpacing.delayMs(nowMs = 1_000, lastStartedAtMs = 900_000, settleMs = settle))
    }

    // Review R16 follow-up: a run that stops at NO_WIFI must not use up the 30 s slot, and a lost Wi-Fi cancels the pending run.

    @Test
    fun `a run while Wi-Fi is definitely down does not start and does not use the slot`() {
        assertFalse(WifiApprovalSpacing.shouldStart(WifiState.NOT_CONNECTED))
    }

    @Test
    fun `a run with Wi-Fi up or unknown starts`() {
        assertTrue(WifiApprovalSpacing.shouldStart(WifiState.CONNECTED))
        assertTrue(WifiApprovalSpacing.shouldStart(WifiState.UNKNOWN))
    }

    @Test
    fun `a skipped no-wifi run leaves the next join at the settle time`() {
        val last = WifiApprovalSpacing.recordStart(null, 100_000, WifiState.NOT_CONNECTED)
        assertEquals(null, last)
        assertEquals(settle, WifiApprovalSpacing.delayMs(nowMs = 108_000, lastStartedAtMs = last, settleMs = settle))
    }

    @Test
    fun `a passing run still holds the next join to thirty seconds`() {
        val last = WifiApprovalSpacing.recordStart(null, 100_000, WifiState.CONNECTED)
        assertEquals(100_000L, last)
        assertEquals(spacing - 8_000, WifiApprovalSpacing.delayMs(nowMs = 108_000, lastStartedAtMs = last, settleMs = settle))
    }

    @Test
    fun `an unknown Wi-Fi state records the start as before`() {
        assertEquals(100_000L, WifiApprovalSpacing.recordStart(5_000L, 100_000, WifiState.UNKNOWN))
    }

    @Test
    fun `a skipped run keeps an earlier recorded start`() {
        assertEquals(70_000L, WifiApprovalSpacing.recordStart(70_000L, 100_000, WifiState.NOT_CONNECTED))
    }
}
