package com.baba.callvault.system.storage

import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R2: the early free-space warning and the "storage is full" wording. */
class FreeSpacePolicyTest {

    private val mb = 1024L * 1024

    @Test
    fun `default opus mono warns at the floor because five hours is only a few tens of megabytes`() {
        val t = FreeSpacePolicy.warnThresholdBytes(ScrcpyAudioCodec.OPUS, 24_000, stereo = false)
        assertEquals(FreeSpacePolicy.WARN_FLOOR_BYTES, t)
    }

    @Test
    fun `high rate stereo AAC warns higher than the floor - five hours of it`() {
        val t = FreeSpacePolicy.warnThresholdBytes(ScrcpyAudioCodec.AAC, 128_000, stereo = true)
        // stereo = max(64k, 2 x 128k) = 256 kb/s = 32 000 bytes/s -> 5 h = 576 000 000
        assertEquals(576_000_000L, t)
    }

    @Test
    fun `an unreadable bit rate falls back to the floor, never to zero`() {
        assertEquals(
            FreeSpacePolicy.WARN_FLOOR_BYTES,
            FreeSpacePolicy.warnThresholdBytes(ScrcpyAudioCodec.AAC, 0, stereo = false),
        )
    }

    @Test
    fun `warns once when free space drops under the threshold`() {
        val t = 300 * mb
        assertEquals(FreeSpacePolicy.Action.WARN, FreeSpacePolicy.decide(100 * mb, t, alreadyWarned = false))
        assertEquals(FreeSpacePolicy.Action.NONE, FreeSpacePolicy.decide(100 * mb, t, alreadyWarned = true))
    }

    @Test
    fun `does not warn when free space is unknown or fine`() {
        val t = 300 * mb
        assertEquals(FreeSpacePolicy.Action.NONE, FreeSpacePolicy.decide(-1L, t, alreadyWarned = false))
        assertEquals(FreeSpacePolicy.Action.NONE, FreeSpacePolicy.decide(t, t, alreadyWarned = false))
    }

    @Test
    fun `re-arms only after free space is clearly back, not on a flap around the threshold`() {
        val t = 300 * mb
        assertEquals(FreeSpacePolicy.Action.NONE, FreeSpacePolicy.decide(t + 10 * mb, t, alreadyWarned = true))
        assertEquals(FreeSpacePolicy.Action.REARM, FreeSpacePolicy.decide(t * 2, t, alreadyWarned = true))
    }

    @Test
    fun `storage full is judged against the waiting store's own minimum`() {
        assertTrue(FreeSpacePolicy.isStorageFull(WaitingPolicy.MIN_FREE_BYTES - 1))
        assertFalse(FreeSpacePolicy.isStorageFull(WaitingPolicy.MIN_FREE_BYTES))
        assertFalse(FreeSpacePolicy.isStorageFull(-1L))
    }

    @Test
    fun `a recording may have been cut short only when almost nothing is left`() {
        assertTrue(FreeSpacePolicy.mayHaveBeenCutShort(5 * mb))
        assertFalse(FreeSpacePolicy.mayHaveBeenCutShort(100 * mb))
        assertFalse(FreeSpacePolicy.mayHaveBeenCutShort(-1L))
    }

    @Test
    fun `the call-start log line has free space and the threshold and no names`() {
        val line = FreeSpacePolicy.callStartLine(free = 250 * mb, threshold = 300 * mb)
        assertEquals("free space at call start: 250 MB (warn below 300 MB) LOW", line)
        assertEquals("free space at call start: unknown", FreeSpacePolicy.callStartLine(-1L, 300 * mb))
    }

    // Review R2 rework: the storage wording must not hide the real cause of an empty file.

    @Test
    fun `empty file after a daemon death keeps the old advice even on a full phone`() {
        val cause = FreeSpacePolicy.emptyCaptureCause(
            lowAtCallStart = true, freeNowBytes = 5 * mb, daemonLossKnown = true, folderOnPhoneStorage = true,
        )
        assertEquals(FreeSpacePolicy.EmptyCause.OTHER, cause)
    }

    @Test
    fun `empty file on a phone that was fine at call start keeps the old advice`() {
        val cause = FreeSpacePolicy.emptyCaptureCause(false, 5 * mb, daemonLossKnown = false, folderOnPhoneStorage = true)
        assertEquals(FreeSpacePolicy.EmptyCause.OTHER, cause)
    }

    @Test
    fun `empty file with plenty of room now keeps the old advice - low at the start is not enough`() {
        val cause = FreeSpacePolicy.emptyCaptureCause(true, 150 * mb, daemonLossKnown = false, folderOnPhoneStorage = true)
        assertEquals(FreeSpacePolicy.EmptyCause.OTHER, cause)
    }

    @Test
    fun `empty file when low at start, almost nothing now, no daemon loss - storage full`() {
        val cause = FreeSpacePolicy.emptyCaptureCause(true, 5 * mb, daemonLossKnown = false, folderOnPhoneStorage = true)
        assertEquals(FreeSpacePolicy.EmptyCause.STORAGE_FULL, cause)
    }

    @Test
    fun `an unreadable free space or a folder on another volume never gives the storage wording`() {
        assertEquals(FreeSpacePolicy.EmptyCause.OTHER, FreeSpacePolicy.emptyCaptureCause(true, -1L, false, true))
        assertEquals(FreeSpacePolicy.EmptyCause.OTHER, FreeSpacePolicy.emptyCaptureCause(true, 5 * mb, false, false))
    }

    @Test
    fun `only the phone's own storage counts as the measured volume`() {
        assertTrue(FreeSpacePolicy.folderOnPhoneStorage(null))
        assertTrue(FreeSpacePolicy.folderOnPhoneStorage("content://com.android.externalstorage.documents/tree/primary%3ARecordings"))
        assertFalse(FreeSpacePolicy.folderOnPhoneStorage("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ARecordings"))
        assertFalse(FreeSpacePolicy.folderOnPhoneStorage("content://com.google.android.apps.docs.storage/document/acc%3D1"))
    }

    @Test
    fun `unknown low-at-start never gives the storage wording`() {
        val cause = FreeSpacePolicy.emptyCaptureCause(null, 5 * mb, daemonLossKnown = false, folderOnPhoneStorage = true)
        assertEquals(FreeSpacePolicy.EmptyCause.OTHER, cause)
    }

    @Test
    fun `a new call forgets the previous call's low reading`() {
        FreeSpaceMonitor.recordLowAtCallStart(true)
        assertEquals(true, FreeSpaceMonitor.lowAtCallStart)
        FreeSpaceMonitor.beginCall()
        assertEquals(null, FreeSpaceMonitor.lowAtCallStart)
    }

    @Test
    fun `a start that failed on an almost full phone is named as storage full (R2 AAC)`() {
        assertTrue(FreeSpacePolicy.startFailureIsStorageFull(freeNowBytes = 144 * 1024L, folderOnPhoneStorage = true))
    }

    @Test
    fun `a start that failed with room to spare keeps the audio format advice`() {
        assertFalse(FreeSpacePolicy.startFailureIsStorageFull(freeNowBytes = 150 * mb, folderOnPhoneStorage = true))
    }

    @Test
    fun `an unreadable free space or another volume never gives the storage wording for a failed start`() {
        assertFalse(FreeSpacePolicy.startFailureIsStorageFull(-1L, true))
        assertFalse(FreeSpacePolicy.startFailureIsStorageFull(5 * mb, false))
    }
}
