/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import com.baba.callvault.data.SyncScheduleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHealthPolicyTest {

    private val now = 1_000L * 60 * 60 * 24 * 100 // day 100

    private fun daysAgo(days: Int) = now - days * 24L * 60L * 60L * 1000L

    @Test
    fun `counts a recording that has waited longer than the schedule allows`() {
        assertEquals(1, SyncHealthPolicy.countStalled(listOf(daysAgo(5)), 0L, SyncScheduleMode.DAILY, now))
    }

    @Test
    fun `does not count a recording still within its window`() {
        assertEquals(0, SyncHealthPolicy.countStalled(listOf(daysAgo(1)), 0L, SyncScheduleMode.DAILY, now))
    }

    @Test
    fun `a weekly schedule tolerates a week of waiting`() {
        // The false positive this threshold exists to prevent: on WEEKLY, six days device-only is
        // correct behaviour, and warning about it would fire every week for ever. A warning that
        // cries wolf is worse than none — it trains the user to swipe away the one that matters.
        assertEquals(0, SyncHealthPolicy.countStalled(listOf(daysAgo(6)), 0L, SyncScheduleMode.WEEKLY, now))
    }

    @Test
    fun `a weekly schedule still warns once it is truly overdue`() {
        assertEquals(1, SyncHealthPolicy.countStalled(listOf(daysAgo(12)), 0L, SyncScheduleMode.WEEKLY, now))
    }

    @Test
    fun `never counts an undated recording`() {
        // A missing stamp is not evidence of age. Counting it would send the user hunting for a
        // problem that is not there.
        assertEquals(0, SyncHealthPolicy.countStalled(listOf(0L), 0L, SyncScheduleMode.IMMEDIATE, now))
    }

    @Test
    fun `counts only the overdue ones in a mixed library`() {
        assertEquals(
            2,
            SyncHealthPolicy.countStalled(
                listOf(daysAgo(30), daysAgo(10), daysAgo(1), 0L), 0L, SyncScheduleMode.DAILY, now
            )
        )
    }

    @Test
    fun `every schedule leaves headroom past its own cycle`() {
        // The invariant behind the numbers: a threshold at or below the cycle length would warn
        // about recordings that are simply waiting their turn.
        assertTrue(SyncHealthPolicy.staleAfterDays(SyncScheduleMode.DAILY) > 1)
        assertTrue(SyncHealthPolicy.staleAfterDays(SyncScheduleMode.WEEKLY) > 7)
    }

    // ---- the false positive reported on 2.2.0 ----

    @Test
    fun `a recording newer than the gap having reached Drive is not a stall`() {
        // Arrange — one old recording never made it, but everything since has.
        val orphan = listOf(daysAgo(30))
        val newestSynced = daysAgo(1)

        // Act
        val stalled = SyncHealthPolicy.countStalled(orphan, newestSynced, SyncScheduleMode.DAILY, now)

        // Assert — copying demonstrably still runs, so claiming it "stopped" would be false.
        assertEquals(0, stalled)
    }

    @Test
    fun `a stall is still caught when nothing newer has reached Drive`() {
        // Arrange — the last thing to reach Drive is older than the recordings waiting.
        val waiting = listOf(daysAgo(4), daysAgo(6))
        val newestSynced = daysAgo(20)

        // Act + Assert — this is the case the warning exists for.
        assertEquals(2, SyncHealthPolicy.countStalled(waiting, newestSynced, SyncScheduleMode.DAILY, now))
    }

    @Test
    fun `only the recordings made since the last successful copy count`() {
        // Arrange — a mixed library: old gaps, then a successful copy, then new failures.
        val unsynced = listOf(daysAgo(40), daysAgo(30), daysAgo(9), daysAgo(8))
        val newestSynced = daysAgo(20)

        // Act
        val stalled = SyncHealthPolicy.countStalled(unsynced, newestSynced, SyncScheduleMode.DAILY, now)

        // Assert — the two before the successful copy are history, not evidence.
        assertEquals(2, stalled)
    }

    @Test
    fun `a library that has never reached Drive still warns`() {
        // Arrange — newestSynced is 0 when no recording has ever been copied.
        assertEquals(
            1,
            SyncHealthPolicy.countStalled(listOf(daysAgo(5)), 0L, SyncScheduleMode.DAILY, now)
        )
    }

    @Test
    fun `a call with no Drive copy is evidence worth counting`() {
        assertTrue(
            SyncHealthPolicy.countsAsUnsynced(
                "20260916_101010.123+0300_in_0501234567.ogg",
                hasLocalCopy = true,
                hasDriveCopy = false,
            )
        )
    }

    @Test
    fun `an imported file is never evidence that copying has stopped`() {
        // It has no Drive copy BY DESIGN and never will, so it is permanently the shape this check
        // reads as a stall. Counting it would tell the user their backup had failed because of a
        // behaviour they asked for — the 2.2.0 false positive, made permanent.
        assertFalse(
            SyncHealthPolicy.countsAsUnsynced(
                "20260916_101010.123+0300_import_voice-note.ogg",
                hasLocalCopy = true,
                hasDriveCopy = false,
            )
        )
    }

    @Test
    fun `a call with a contact called Important still counts`() {
        // The exemption is read from the marker slot, not from the word appearing in the name. If it
        // were looser, this call would silently stop being watched over.
        assertTrue(
            SyncHealthPolicy.countsAsUnsynced(
                "20260916_101010.123+0300_in_Important.ogg",
                hasLocalCopy = true,
                hasDriveCopy = false,
            )
        )
    }

    @Test
    fun `a recording that reached Drive is proof rather than a symptom`() {
        assertFalse(
            SyncHealthPolicy.countsAsUnsynced(
                "20260916_101010.123+0300_in_0501234567.ogg",
                hasLocalCopy = true,
                hasDriveCopy = true,
            )
        )
    }

    @Test
    fun `a Drive-only recording has no device copy to be waiting`() {
        assertFalse(
            SyncHealthPolicy.countsAsUnsynced(
                "20260916_101010.123+0300_in_0501234567.ogg",
                hasLocalCopy = false,
                hasDriveCopy = false,
            )
        )
    }
}
