/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.work.ExistingPeriodicWorkPolicy
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plain re-apply of the sweep schedule must not move the next run (BabApps item 138). */
class SyncSchedulerDecisionTest {

    private val sig = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.DAILY, 15, 45, 1)

    @Test
    fun `unchanged signature is kept`() {
        val d = SyncScheduler.decide(sig, sig)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, d.policy)
        assertEquals(SyncScheduler.Reason.UNCHANGED, d.reason)
    }

    @Test
    fun `update is never chosen`() {
        for (o in listOf(null, sig, "x")) {
            assertNotEquals(ExistingPeriodicWorkPolicy.UPDATE, SyncScheduler.decide(o, sig).policy)
        }
    }

    @Test
    fun `each changed field re-enqueues`() {
        val changed = listOf(
            SyncScheduler.signature(StorageTarget.DRIVE, SyncScheduleMode.DAILY, 15, 45, 1),
            SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.WEEKLY, 15, 45, 1),
            SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.DAILY, 16, 45, 1),
            SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.DAILY, 15, 46, 1),
        )
        for (c in changed) {
            val d = SyncScheduler.decide(sig, c)
            assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, d.policy)
            assertEquals(SyncScheduler.Reason.CHANGED, d.reason)
        }
        // the weekly day matters only in WEEKLY mode
        val w1 = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.WEEKLY, 15, 45, 1)
        val w2 = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.WEEKLY, 15, 45, 2)
        assertEquals(SyncScheduler.Reason.CHANGED, SyncScheduler.decide(w1, w2).reason)
    }

    @Test
    fun `first ever apply has no stored signature and enqueues`() {
        val d = SyncScheduler.decide(null, sig)
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, d.policy)
    }

    @Test
    fun `journal wording names what WorkManager did and the minutes`() {
        val unchanged = SyncScheduler.decide(sig, sig)
        val keep = SyncScheduler.describe(unchanged, sig, sig, 2, enqueuedNow = false, nextMin = 612)
        assertTrue(keep, keep.startsWith("signature unchanged → KEEP, next run in 612 min"))

        val gone = SyncScheduler.describe(unchanged, sig, sig, 5, enqueuedNow = true, nextMin = 5)
        assertTrue(gone, gone.contains("no live request") && gone.contains("re-enqueued, first run in 5 min"))

        val other = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.DAILY, 16, 45, 1)
        val re = SyncScheduler.describe(SyncScheduler.decide(sig, other), sig, other, 90, enqueuedNow = true, nextMin = 90)
        assertTrue(re, re.startsWith("signature changed (from '$sig' to '$other') → re-enqueued, first run in 90 min"))

        val unreadable = SyncScheduler.describe(unchanged, sig, sig, 5, null, null, "TimeoutException: null")
        assertTrue(unreadable, unreadable.contains("KEEP asked") && unreadable.contains("could not be read back: TimeoutException"))
    }
}
