package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** N30: the user being heard wins over our recording. */
class MicYieldTest {

    private fun dump(vararg lines: String) = lines.joinToString("\n")

    private val whatsAppStart = "08-31 20:13:49:297 rec start riid:43847 uid:10394 session:54905 src:MIC not silenced pack:com.whatsapp"
    private val whatsAppSilenced = "08-31 20:13:50:100 rec update riid:43847 uid:10394 session:54905 src:MIC silenced pack:com.whatsapp"
    private val ourMic = "08-31 20:13:50:000 rec start riid:43900 uid:2000 session:54999 src:MIC not silenced pack:com.android.shell"

    // ---- the decision ----

    @Test
    fun `the yield is switched off by default`() {
        assertEquals(false, MicYield.YIELD_ENABLED)
    }

    @Test
    fun `with the yield on, the call app silenced after our re-take means we yield the mic`() {
        assertEquals(MicYield.Action.YIELD, MicYield.decide(callAppSilenced = true, yieldEnabled = true))
    }

    @Test
    fun `with the yield on, a call app not silenced or unknown leaves the re-take unchanged`() {
        assertEquals(MicYield.Action.KEEP, MicYield.decide(callAppSilenced = false, yieldEnabled = true))
        assertEquals(MicYield.Action.KEEP, MicYield.decide(callAppSilenced = null, yieldEnabled = true))
    }

    @Test
    fun `with the yield off, nothing ever changes the re-take behaviour`() {
        assertEquals(MicYield.Action.KEEP, MicYield.decide(callAppSilenced = true, yieldEnabled = false))
        assertEquals(MicYield.Action.KEEP, MicYield.decide(callAppSilenced = true))
    }

    // ---- reading the signal from the audio dump ----

    @Test
    fun `a silenced call app capture is read as silenced`() {
        val s = MicYield.callAppState(dump(whatsAppStart, ourMic, whatsAppSilenced), uid = 10394)
        assertEquals(true, s?.silenced)
        assertEquals("com.whatsapp", s?.pack)
        assertEquals("MIC", s?.source)
    }

    @Test
    fun `a call app capture that is not silenced is read as not silenced`() {
        assertEquals(false, MicYield.callAppState(dump(whatsAppStart, ourMic), uid = 10394)?.silenced)
    }

    @Test
    fun `only the latest event of a capture counts`() {
        val unsilenced = "08-31 20:13:52:000 rec update riid:43847 uid:10394 session:54905 src:MIC not silenced pack:com.whatsapp"
        assertEquals(false, MicYield.callAppState(dump(whatsAppStart, whatsAppSilenced, unsilenced), uid = 10394)?.silenced)
    }

    @Test
    fun `a stopped capture is not a live one`() {
        val stop = "08-31 20:13:55:296 rec stop riid:43847 uid:10394 session:54905 src:MIC silenced pack:com.whatsapp"
        assertNull(MicYield.callAppState(dump(whatsAppStart, whatsAppSilenced, stop), uid = 10394))
    }

    @Test
    fun `another uid's silenced capture is not the call app's`() {
        val other = "08-31 20:13:50:100 rec update riid:1 uid:10555 session:5 src:MIC silenced pack:com.other"
        assertNull(MicYield.callAppState(dump(whatsAppStart.replace("not silenced", "not silenced"), other), uid = 10999))
        assertEquals(false, MicYield.callAppState(dump(whatsAppStart, other), uid = 10394)?.silenced)
    }

    @Test
    fun `an empty or unrelated dump answers unknown`() {
        assertNull(MicYield.callAppState("", uid = 10394))
        assertNull(MicYield.callAppState("nothing here", uid = 10394))
    }

    @Test
    fun `our own shell capture is never the call app`() {
        assertNull(MicYield.callAppState(dump(ourMic), uid = 2000))
    }

    // ---- the evidence line carried to the app ----

    @Test
    fun `the evidence line round trips through the capture health text`() {
        val state = MicYield.ClientState("com.whatsapp", "MIC", true)
        val line = MicYield.evidenceLine(state, atMs = 12_345L, retakes = 3, checkMs = 220L)
        val health = "VoIP sync summary ... || $line"
        val found = MicYield.evidenceIn(health)
        assertNotNull(found)
        assertEquals("com.whatsapp", MicYield.packOf(found!!))
    }

    @Test
    fun `health text without the marker has no evidence`() {
        assertNull(MicYield.evidenceIn("VoIP sync: all fine"))
        assertNull(MicYield.evidenceIn(null))
        assertNull(MicYield.evidenceIn(""))
    }

    @Test
    fun `an observation line says it kept re-taking and a yield line says it yielded`() {
        val state = MicYield.ClientState("com.whatsapp", "MIC", true)
        val kept = MicYield.evidenceLine(state, 1L, 1, 1L)
        val yielded = MicYield.evidenceLine(state, 1L, 1, 1L, MicYield.Action.YIELD)
        assertEquals(false, MicYield.wasYielded(kept))
        assertEquals(true, MicYield.wasYielded(yielded))
    }
}
