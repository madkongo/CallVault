/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plain re-apply of the sweep schedule must not move the next run (BabApps item 138). */
class SyncSchedulerDecisionTest {

    private val sig = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.DAILY, 15, 45, 1)
    private val enqueued = listOf(WorkInfo.State.ENQUEUED)

    @Test
    fun `unchanged signature and a live request is kept`() {
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)) {
            val d = SyncScheduler.decide(sig, sig, listOf(state))
            assertEquals(ExistingPeriodicWorkPolicy.KEEP, d.policy)
            assertEquals(SyncScheduler.Reason.UNCHANGED_ALIVE, d.reason)
        }
    }

    @Test
    fun `update is never chosen`() {
        val olds = listOf(null, sig, "x")
        val states = listOf(null, emptyList(), enqueued, listOf(WorkInfo.State.CANCELLED), listOf(WorkInfo.State.FAILED))
        for (o in olds) for (s in states) {
            assertNotEquals(ExistingPeriodicWorkPolicy.UPDATE, SyncScheduler.decide(o, sig, s).policy)
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
            val d = SyncScheduler.decide(sig, c, enqueued)
            assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, d.policy)
            assertEquals(SyncScheduler.Reason.CHANGED, d.reason)
        }
        // the weekly day matters only in WEEKLY mode
        val w1 = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.WEEKLY, 15, 45, 1)
        val w2 = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.WEEKLY, 15, 45, 2)
        assertEquals(SyncScheduler.Reason.CHANGED, SyncScheduler.decide(w1, w2, enqueued).reason)
    }

    @Test
    fun `first ever apply has no stored signature and enqueues`() {
        val d = SyncScheduler.decide(null, sig, emptyList())
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, d.policy)
    }

    @Test
    fun `missing, cancelled or failed request is re-enqueued even when unchanged`() {
        for (states in listOf(emptyList(), listOf(WorkInfo.State.CANCELLED), listOf(WorkInfo.State.FAILED))) {
            val d = SyncScheduler.decide(sig, sig, states)
            assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, d.policy)
            assertEquals(SyncScheduler.Reason.NO_LIVE_WORK, d.reason)
        }
    }

    @Test
    fun `an unreadable request state keeps, which never moves an existing run`() {
        val d = SyncScheduler.decide(sig, sig, null)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, d.policy)
        assertEquals(SyncScheduler.Reason.WORK_STATE_UNREADABLE, d.reason)
    }

    @Test
    fun `journal wording names the decision and the minutes`() {
        val keep = SyncScheduler.describe(SyncScheduler.decide(sig, sig, enqueued), sig, sig, 2, 612)
        assertTrue(keep, keep.startsWith("signature unchanged → KEEP, next run in 612 min"))

        val other = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.DAILY, 16, 45, 1)
        val re = SyncScheduler.describe(SyncScheduler.decide(sig, other, enqueued), sig, other, 90, null)
        assertTrue(re, re.startsWith("signature changed (from '$sig' to '$other') → re-enqueued, first run in 90 min"))

        val gone = SyncScheduler.describe(SyncScheduler.decide(sig, sig, emptyList()), sig, sig, 5, null)
        assertTrue(gone, gone.contains("no live request") && gone.contains("re-enqueued, first run in 5 min"))

        val unreadable = SyncScheduler.describe(SyncScheduler.decide(sig, sig, null), sig, sig, 5, null)
        assertTrue(unreadable, unreadable.contains("could not be read → KEEP"))
    }
}
