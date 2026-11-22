package com.baba.callvault.system.storage

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N10: the Drive sweep skips only header-only stubs; every other name is copied. */
class SweepSelectionTest {

    private val ours = "20260825_130207.420+0300_out_Someone.ogg"

    @Test
    fun `a recording is copied`() = assertNull(CloudCopyPolicy.noAudioStubReason(ours, 50_000L))

    @Test
    fun `a stub with no audio is skipped with a reason`() {
        val reason = CloudCopyPolicy.noAudioStubReason(ours, 191L)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("no audio"))
    }

    @Test
    fun `the stub limit is the rescue limit`() {
        assertNotNull(CloudCopyPolicy.noAudioStubReason(ours, CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES - 1))
        assertNull(CloudCopyPolicy.noAudioStubReason(ours, CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES))
    }

    @Test
    fun `a foreign or renamed audio file of normal size is still copied`() {
        assertNull(CloudCopyPolicy.noAudioStubReason("audio_2024-03-05_14-22-10.ogg", 50_000L))
        assertNull(CloudCopyPolicy.noAudioStubReason("20260825_130207.420+0300_out_Someone (1).ogg", 50_000L))
    }

    @Test
    fun `a small sidecar is not a stub`() =
        assertNull(CloudCopyPolicy.noAudioStubReason("20260825_130207.420+0300_out_Someone.json", 40L))
}
