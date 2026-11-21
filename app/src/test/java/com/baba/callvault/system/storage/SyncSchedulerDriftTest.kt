/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review C152: when a waiting sweep has drifted off the chosen HH:mm, and when it must be left alone. */
class SyncSchedulerDriftTest {

    private val now = 1_000_000_000_000L
    private val minute = 60_000L
    private val anchor = now + 600 * minute // 10 h ahead

    private fun check(
        state: WorkInfo.State = WorkInfo.State.ENQUEUED,
        attempts: Int = 0,
        next: Long = anchor,
    ) = SyncScheduler.needsReanchor(state, attempts, next, anchor, now)

    @Test
    fun `a run on the chosen time is left alone`() = assertFalse(check())

    @Test
    fun `a few minutes of slack is left alone`() {
        assertFalse(check(next = anchor + 9 * minute))
        assertFalse(check(next = anchor - 9 * minute))
    }

    @Test
    fun `a run that drifted later than the tolerance is put back`() = assertTrue(check(next = anchor + 11 * minute))

    @Test
    fun `a run an hour off after a DST change is put back`() {
        assertTrue(check(next = anchor + 60 * minute))
        assertTrue(check(next = anchor - 60 * minute))
    }

    @Test
    fun `a running sweep is never touched`() = assertFalse(check(state = WorkInfo.State.RUNNING, next = anchor + 90 * minute))

    @Test
    fun `a pending retry backoff is never overridden`() = assertFalse(check(attempts = 1, next = anchor + 90 * minute))

    @Test
    fun `a run that is already due and waiting for the network is not pushed out`() {
        assertFalse(check(next = now - 30 * minute))
        assertFalse(check(next = now))
    }

    @Test
    fun `no known next run is left alone`() = assertFalse(check(next = Long.MAX_VALUE))

    @Test
    fun `other states are left alone`() {
        for (s in listOf(WorkInfo.State.BLOCKED, WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            assertFalse(s.name, check(state = s, next = anchor + 90 * minute))
        }
    }
}
