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
        assertEquals(0, DriveCopyHandler.TEST_PAD_LIST_TO)
        assertFalse(DriveCopyHandler.TEST_SEND_LIST_INLINE)
    }

    // --- drive3: a listing too big for a Messenger reply (BabApps item 112, log 61) ---

    private fun bigListing(entries: Int): Triple<List<String>, List<String>, List<Long>> = Triple(
        List(entries) { "call_2026-11-${it % 28}_+97250${it.toString().padStart(7, '0')}_out.ogg" },
        List(entries) { "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3D$it" },
        List(entries) { 50_000L + it },
    )

    @Test
    fun `a 3500 entry listing is far over what a binder reply carries, and still round-trips through the file form`() {
        val (names, uris, sizes) = bigListing(3500)
        val out = java.io.ByteArrayOutputStream()
        ListingCodec.write(names, uris, sizes, out)
        // A Bundle parcel holds strings as UTF-16 (2 bytes a character) plus a header per string. The reply that
        // failed on a real phone was 654348 bytes for 1484 entries; the binder carries about half of 1 MB.
        val asBundleBytes = names.sumOf { 2L * it.length + 8 } + uris.sumOf { 2L * it.length + 8 } + 8L * sizes.size
        assertTrue("as a Bundle this would be ~$asBundleBytes bytes", asBundleBytes > 512 * 1024)
        val (n2, u2, s2) = ListingCodec.read(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(names, n2)
        assertEquals(uris, u2)
        assertEquals(sizes, s2)
    }

    @Test
    fun `an empty listing and a truncated file are handled, not mistaken for a full list`() {
        val out = java.io.ByteArrayOutputStream()
        ListingCodec.write(emptyList(), emptyList(), emptyList(), out)
        assertEquals(0, ListingCodec.read(java.io.ByteArrayInputStream(out.toByteArray())).first.size)

        val (names, uris, sizes) = bigListing(50)
        val full = java.io.ByteArrayOutputStream().also { ListingCodec.write(names, uris, sizes, it) }.toByteArray()
        val cut = full.copyOf(full.size / 2)
        val failed = runCatching { ListingCodec.read(java.io.ByteArrayInputStream(cut)) }
        assertTrue("a half-written list must fail to read", failed.isFailure)
    }

    @Test
    fun `a reply the binder refuses becomes a small failure that names the size`() {
        val reason = DriveCopyProtocol.tooLargeReason(1484, 654_348, "TransactionTooLargeException: data parcel size 654348 bytes")
        assertTrue(reason.startsWith("reply too large: 1484 entries, 654348 bytes"))
        assertTrue(reason.length < 1000) // the failure reply itself must always fit
    }

    @Test
    fun `the app waits seconds for a listing, not minutes`() {
        assertTrue(CloudCopyPolicy.LIST_TIMEOUT_MS <= 30_000L)
        assertTrue(CloudCopyPolicy.LIST_TIMEOUT_MS < CloudCopyPolicy.MIN_FILE_TIMEOUT_MS)
    }

    @Test
    fun `a copier that failed to list today is not asked again today, but is tomorrow`() {
        assertTrue(CloudCopyPolicy.copierListFailedToday("2026-11-17", "2026-11-17"))
        assertFalse(CloudCopyPolicy.copierListFailedToday("2026-11-17", "2026-11-18"))
        assertFalse(CloudCopyPolicy.copierListFailedToday(null, "2026-11-17"))
    }

    @Test
    fun `a Drive copy reported as 0 or unknown length is not verified and is checked again, never deleted on`() {
        assertTrue(CloudCopyPolicy.isVerifiedPresent(4096L, 4096L))
        assertFalse(CloudCopyPolicy.isVerifiedPresent(0L, 4096L)) // settled-at-zero: ask the copier again next sweep
        assertFalse(CloudCopyPolicy.isVerifiedPresent(-1L, 4096L))
        assertFalse(CloudCopyPolicy.isVerifiedPresent(1000L, 4096L)) // truncated
        assertFalse(CloudCopyPolicy.isVerifiedPresent(0L, 0L))
        // and the delete rule agrees with it: the same evidence never allows a delete
        for (size in listOf(0L, -1L, 1000L)) assertFalse(CloudCopyPolicy.mayDeleteLocal(size, 4096L))
    }
}
