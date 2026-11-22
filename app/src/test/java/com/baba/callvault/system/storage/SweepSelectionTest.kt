package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N10: the Drive sweep copies only what CallVault wrote, and never a stub with no audio. */
class SweepSelectionTest {

    private val template = "{date}_{direction}_{contact_name}"
    private val ours = "20260825_130207.420+0300_out_Someone.ogg"

    private fun skip(name: String, size: Long = 50_000L, merged: Set<String> = emptySet()) =
        CloudCopyPolicy.sweepSkipReason(name, size, template, merged)

    @Test
    fun `a recording CallVault wrote is copied`() = assertNull(skip(ours))

    @Test
    fun `a foreign audio file is skipped with a reason`() {
        val reason = skip("audio_2024-03-05_14-22-10.ogg")
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("not written by CallVault"))
    }

    @Test
    fun `a stub with no audio is skipped`() {
        val reason = skip(ours, size = 191L)
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("no audio"))
    }

    @Test
    fun `the stub limit is the rescue limit`() {
        assertNotNull(skip(ours, size = CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES - 1))
        assertNull(skip(ours, size = CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES))
    }

    @Test
    fun `a small sidecar of ours is not a stub`() =
        assertNull(skip("20260825_130207.420+0300_out_Someone.json", size = 40L))

    @Test
    fun `a merge output with any name is copied`() =
        assertNull(skip("my merged call.ogg", merged = setOf("my merged call.ogg")))

    @Test
    fun `a voip recording is copied`() =
        assertNull(skip("20260824_112330.671+0300_voip-WhatsApp_Someone.ogg"))

    @Test
    fun `the foreign verdict wins over size`() =
        assertEquals(true, skip("random.mp3", size = 10L)!!.contains("not written by CallVault"))
}
