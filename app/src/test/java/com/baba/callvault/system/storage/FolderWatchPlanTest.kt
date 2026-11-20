/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2026-11-20, owner concern: users report battery drain, so the folder check adds no timer to a phone in a good
 * state that already has a trigger, and checks are skipped while fresh.
 */
class FolderWatchPlanTest {

    @Test
    fun `a good folder gets no timer in any mode - app start, Home and the keep-alive tick cover it`() {
        assertNull(FolderWatchPlan.periodMinutes(waitingCount = 0, folderBad = false))
    }

    @Test
    fun `a bad folder or waiting calls get the trouble period`() {
        assertEquals(FolderWatchPlan.TROUBLE_MINUTES, FolderWatchPlan.periodMinutes(waitingCount = 0, folderBad = true))
        assertEquals(FolderWatchPlan.TROUBLE_MINUTES, FolderWatchPlan.periodMinutes(waitingCount = 2, folderBad = false))
    }

    private fun state(kind: FolderReading.Kind, measuredAt: Long, firstBadAt: Long = 0L) =
        FolderCheckState("u", kind, measuredAt, firstBadAt, confirmedUnusable = false, alerted = false)

    @Test
    fun `a good answer is measured again only after the caller's own age`() {
        val good = state(FolderReading.Kind.OK, measuredAt = 1_000L)
        assertFalse(FolderWatchPlan.isStale(good, now = 1_000L + 59 * 60_000L, goodMaxAgeMs = FolderWatchPlan.KEEP_ALIVE_GOOD_MAX_AGE_MS))
        assertTrue(FolderWatchPlan.isStale(good, now = 1_000L + 60 * 60_000L, goodMaxAgeMs = FolderWatchPlan.KEEP_ALIVE_GOOD_MAX_AGE_MS))
        assertTrue(FolderWatchPlan.isStale(null, now = 0L, goodMaxAgeMs = FolderWatchPlan.KEEP_ALIVE_GOOD_MAX_AGE_MS))
    }

    @Test
    fun `a bad answer backs off - 5, 10, 30, then 60 minutes - instead of probing every tick`() {
        val min = 60_000L
        fun staleAfter(badForMin: Long, ageMin: Long): Boolean {
            val firstBad = 1_000_000_000L
            val measured = firstBad + badForMin * min
            return FolderWatchPlan.isStale(state(FolderReading.Kind.TRANSIENT, measured, firstBad), now = measured + ageMin * min, goodMaxAgeMs = 5 * min)
        }
        assertFalse(staleAfter(badForMin = 0, ageMin = 1))
        assertTrue(staleAfter(badForMin = 0, ageMin = 5))
        assertFalse(staleAfter(badForMin = 40, ageMin = 9))
        assertTrue(staleAfter(badForMin = 40, ageMin = 10))
        assertFalse(staleAfter(badForMin = 3 * 60, ageMin = 29))
        assertTrue(staleAfter(badForMin = 3 * 60, ageMin = 30))
        assertFalse(staleAfter(badForMin = 10 * 60, ageMin = 59))
        assertTrue(staleAfter(badForMin = 10 * 60, ageMin = 60))
    }

    @Test
    fun `the keep-alive looks at a good folder at most hourly`() {
        assertEquals(60 * 60_000L, FolderWatchPlan.KEEP_ALIVE_GOOD_MAX_AGE_MS)
    }
}
