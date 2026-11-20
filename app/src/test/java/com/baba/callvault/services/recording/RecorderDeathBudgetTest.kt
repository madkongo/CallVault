/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R7 step 2: a recorder that keeps dying seconds after it starts must not be relaunched back to back. */
class RecorderDeathBudgetTest {

    private fun budget() = RecorderDeathBudget(maxShortDeaths = 3, windowMs = 10 * 60_000L, shortLifeMs = 60_000L)

    @Test
    fun `a recorder that lived a while dies without touching the budget`() {
        val b = budget()
        b.onConnected(now = 0L)
        repeat(10) { i -> assertFalse(b.onDeath(now = 120_000L + i * 200_000L).tripped) }
    }

    @Test
    fun `the first short-lived deaths still relaunch at once`() {
        val b = budget()
        b.onConnected(0L); assertFalse(b.onDeath(10_000L).tripped)
        b.onConnected(11_000L); assertFalse(b.onDeath(20_000L).tripped)
    }

    @Test
    fun `the third short-lived death in the window trips the budget`() {
        val b = budget()
        b.onConnected(0L); b.onDeath(10_000L)
        b.onConnected(11_000L); b.onDeath(20_000L)
        b.onConnected(21_000L)
        val verdict = b.onDeath(30_000L)
        assertTrue(verdict.tripped)
        assertEquals(3, verdict.shortDeathsInWindow)
    }

    @Test
    fun `only the first trip of a run says so, so the log gets one line`() {
        val b = budget()
        b.onConnected(0L); b.onDeath(10_000L)
        b.onConnected(11_000L); b.onDeath(20_000L)
        b.onConnected(21_000L)
        assertTrue(b.onDeath(30_000L).firstOfRun)
        b.onConnected(31_000L)
        val again = b.onDeath(40_000L)
        assertTrue(again.tripped)
        assertFalse(again.firstOfRun)
    }

    @Test
    fun `short deaths older than the window are forgotten`() {
        val b = budget()
        b.onConnected(0L); b.onDeath(10_000L)
        b.onConnected(11_000L); b.onDeath(20_000L)
        b.onConnected(11 * 60_000L)
        assertFalse(b.onDeath(11 * 60_000L + 5_000L).tripped)
    }

    @Test
    fun `a recorder that then lives long clears the run`() {
        val b = budget()
        b.onConnected(0L); b.onDeath(10_000L)
        b.onConnected(11_000L); b.onDeath(20_000L)
        b.onConnected(21_000L); assertTrue(b.onDeath(30_000L).tripped)
        b.onConnected(40_000L)
        assertFalse(b.onDeath(40_000L + 5 * 60_000L).tripped)
        // The next short death starts a fresh count.
        b.onConnected(400_000L)
        assertFalse(b.onDeath(405_000L).tripped)
    }

    @Test
    fun `a death with no known connect time is not counted as short`() {
        assertFalse(budget().onDeath(1_000L).tripped)
    }
}
