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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The limits that stop one stuck Drive file from holding a whole backlog (BabApps item 112). */
class DriveCopyLimitsTest {

    @Test
    fun `a small file gets the minimum time and no less`() {
        assertEquals(CloudCopyPolicy.MIN_FILE_TIMEOUT_MS, CloudCopyPolicy.fileTimeoutMs(0L))
        assertEquals(CloudCopyPolicy.MIN_FILE_TIMEOUT_MS, CloudCopyPolicy.fileTimeoutMs(-5L))
        assertEquals(CloudCopyPolicy.MIN_FILE_TIMEOUT_MS, CloudCopyPolicy.fileTimeoutMs(500_000L))
    }

    @Test
    fun `a bigger file gets more time, by the megabyte`() {
        val mb = 1024L * 1024L
        assertEquals(
            CloudCopyPolicy.MIN_FILE_TIMEOUT_MS + 10 * CloudCopyPolicy.PER_MEGABYTE_MS,
            CloudCopyPolicy.fileTimeoutMs(10 * mb),
        )
        assertTrue(CloudCopyPolicy.fileTimeoutMs(50 * mb) > CloudCopyPolicy.fileTimeoutMs(5 * mb))
    }

    @Test
    fun `the time limit is a few minutes for an ordinary recording, not twenty`() {
        val twentyMinutes = 20 * 60_000L
        assertTrue(CloudCopyPolicy.fileTimeoutMs(5L * 1024 * 1024) < twentyMinutes)
    }

    @Test
    fun `only the copier process skips the app's own start-up`() {
        assertTrue(DriveCopyProtocol.isCopierProcess("com.baba.callvault" + DriveCopyProtocol.PROCESS_SUFFIX))
        assertFalse(DriveCopyProtocol.isCopierProcess("com.baba.callvault"))
        assertFalse(DriveCopyProtocol.isCopierProcess(null))
    }

    @Test
    fun `the sweep gives up on a dead Drive after a few files, not after the whole backlog`() {
        assertTrue(CloudCopyPolicy.MAX_CONSECUTIVE_STALLS in 2..5)
    }

    @Test
    fun `pace is files per minute`() {
        assertEquals("10.0", DriveCopyJournal.pace(10, 60_000L))
        assertEquals("n/a", DriveCopyJournal.pace(10, 0L))
    }

    @Test
    fun `a sweep that cannot list retries a bounded number of times and never waits hours`() {
        // The last retry is the one before the limit; the attempt at the limit waits for the schedule.
        assertTrue(CloudCopyPolicy.shouldRetryListFailure(0))
        assertTrue(CloudCopyPolicy.shouldRetryListFailure(CloudCopyPolicy.LIST_FAILURE_MAX_ATTEMPTS - 2))
        assertFalse(CloudCopyPolicy.shouldRetryListFailure(CloudCopyPolicy.LIST_FAILURE_MAX_ATTEMPTS - 1))
        assertFalse(CloudCopyPolicy.shouldRetryListFailure(CloudCopyPolicy.LIST_FAILURE_MAX_ATTEMPTS + 5))
        // Linear backoff: the longest single wait is the delay times the last attempt count. drive1's was 5 hours.
        val longestWaitMinutes = CloudCopyPolicy.SWEEP_BACKOFF_MINUTES * CloudCopyPolicy.LIST_FAILURE_MAX_ATTEMPTS
        assertTrue(longestWaitMinutes < 60)
    }

    @Test
    fun `the device copy is deleted only when Drive reports exactly the same length`() {
        assertTrue(CloudCopyPolicy.mayDeleteLocal(driveReportedSize = 4096L, sourceSize = 4096L))
        assertFalse(CloudCopyPolicy.mayDeleteLocal(driveReportedSize = 4095L, sourceSize = 4096L)) // truncated
        assertFalse(CloudCopyPolicy.mayDeleteLocal(driveReportedSize = -1L, sourceSize = 4096L)) // provider will not say
        assertFalse(CloudCopyPolicy.mayDeleteLocal(driveReportedSize = 0L, sourceSize = 4096L)) // still settling
        assertFalse(CloudCopyPolicy.mayDeleteLocal(driveReportedSize = 0L, sourceSize = 0L)) // nothing to prove
    }

    @Test
    fun `a changed time or mode is a changed schedule, a plain re-apply is not`() {
        val a = SyncScheduler.signature(com.baba.callvault.data.StorageTarget.BOTH, com.baba.callvault.data.SyncScheduleMode.DAILY, 15, 13, 1)
        assertEquals(a, SyncScheduler.signature(com.baba.callvault.data.StorageTarget.BOTH, com.baba.callvault.data.SyncScheduleMode.DAILY, 15, 13, 5))
        assertTrue(a != SyncScheduler.signature(com.baba.callvault.data.StorageTarget.BOTH, com.baba.callvault.data.SyncScheduleMode.DAILY, 15, 15, 1))
        assertTrue(a != SyncScheduler.signature(com.baba.callvault.data.StorageTarget.DRIVE, com.baba.callvault.data.SyncScheduleMode.DAILY, 15, 13, 1))
        assertTrue(a != SyncScheduler.signature(com.baba.callvault.data.StorageTarget.BOTH, com.baba.callvault.data.SyncScheduleMode.WEEKLY, 15, 13, 1))
    }

    @Test
    fun `the forced list failure test hook is never committed switched on`() {
        assertFalse(DriveCopyHandler.TEST_FORCE_LIST_FAILURE)
    }
}
