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
}
