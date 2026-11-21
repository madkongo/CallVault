/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
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
}
