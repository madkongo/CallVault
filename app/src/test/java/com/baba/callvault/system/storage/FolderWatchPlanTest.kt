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
    fun `a good folder on a phone with the keep-alive gets no timer`() {
        assertNull(FolderWatchPlan.periodMinutes(keepAliveRuns = true, recordingOn = true, waitingCount = 0, folderBad = false))
    }

    @Test
    fun `a good folder with recording off gets no timer`() {
        assertNull(FolderWatchPlan.periodMinutes(keepAliveRuns = false, recordingOn = false, waitingCount = 0, folderBad = false))
    }

    @Test
    fun `a good folder with no keep-alive (Shizuku mode) gets the long period only`() {
        assertEquals(
            FolderWatchPlan.GOOD_NO_KEEP_ALIVE_MINUTES,
            FolderWatchPlan.periodMinutes(keepAliveRuns = false, recordingOn = true, waitingCount = 0, folderBad = false),
        )
    }

    @Test
    fun `a bad folder or waiting calls get the shorter period, in every mode`() {
        assertEquals(FolderWatchPlan.TROUBLE_MINUTES, FolderWatchPlan.periodMinutes(true, true, 0, folderBad = true))
        assertEquals(FolderWatchPlan.TROUBLE_MINUTES, FolderWatchPlan.periodMinutes(true, false, waitingCount = 2, folderBad = false))
        assertTrue(FolderWatchPlan.TROUBLE_MINUTES < FolderWatchPlan.GOOD_NO_KEEP_ALIVE_MINUTES)
    }

    @Test
    fun `a fresh good measurement is not taken again, an old or bad one is`() {
        val good = FolderCheckState("u", FolderReading.Kind.OK, measuredAt = 1_000L, firstBadAt = 0L, confirmedUnusable = false, alerted = false)
        assertFalse(FolderWatchPlan.isStale(good, now = 1_000L + 60_000L, maxAgeMs = 5 * 60_000L))
        assertTrue(FolderWatchPlan.isStale(good, now = 1_000L + 5 * 60_000L, maxAgeMs = 5 * 60_000L))
        assertTrue(FolderWatchPlan.isStale(null, now = 0L, maxAgeMs = 5 * 60_000L))
        assertTrue(FolderWatchPlan.isStale(good.copy(kind = FolderReading.Kind.MISSING), now = 1_001L, maxAgeMs = 5 * 60_000L))
    }
}
