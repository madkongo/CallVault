package com.baba.callvault.integrations.adb

import com.baba.callvault.utils.LogRedaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RevivalTraceTest {

    @Test fun `a line identical to the previous one is skipped`() {
        val gate = TraceGate(now = { 0L })
        assertTrue(gate.admit("a"))
        assertFalse(gate.admit("a"))
        assertTrue(gate.admit("b"))
        assertTrue(gate.admit("a"))
    }

    @Test fun `a flood of different lines is capped per window then resumes`() {
        var t = 0L
        val gate = TraceGate(now = { t }, maxPerWindow = 3, windowMs = 1_000)
        assertTrue(gate.admit("1"))
        assertTrue(gate.admit("2"))
        assertTrue(gate.admit("3"))
        assertFalse(gate.admit("4"))
        t = 1_001
        assertTrue(gate.admit("5"))
    }

    @Test fun `an always line bypasses dedupe and the cap`() {
        val gate = TraceGate(now = { 0L }, maxPerWindow = 1)
        assertTrue(gate.admit("x"))
        assertTrue(gate.admit("x", always = true))
        assertTrue(gate.admit("x", always = true))
    }

    @Test fun `failure cause names class and message`() {
        assertEquals("IOException: broken pipe", TraceText.failureCause(IOException("broken pipe")))
    }

    @Test fun `failure cause with no message and with no exception`() {
        assertEquals("IllegalStateException: (no message)", TraceText.failureCause(IllegalStateException()))
        assertEquals("returned false (no exception)", TraceText.failureCause(null))
    }

    @Test fun `failure cause is bounded`() {
        assertTrue(TraceText.failureCause(IOException("x".repeat(5_000))).length < 300)
    }

    @Test fun `read back formatting`() {
        assertEquals("adb_wifi_enabled read-back=0", TraceText.readBack(0))
        assertEquals("adb_wifi_enabled read-back=1", TraceText.readBack(1))
        assertEquals("adb_wifi_enabled read-back=unreadable", TraceText.readBack(null))
    }

    @Test fun `raw property shows empty distinctly`() {
        assertEquals("(empty)", TraceText.raw(""))
        assertEquals("restarting", TraceText.raw(" restarting "))
    }

    @Test fun `down period writes once then again after recovery`() {
        val g = OncePerDownPeriod()
        assertFalse(g.shouldWrite(down = false))
        assertTrue(g.shouldWrite(down = true))
        assertFalse(g.shouldWrite(down = true))
        assertFalse(g.shouldWrite(down = false))
        assertTrue(g.shouldWrite(down = true))
    }

    @Test fun `numbers in trace lines survive redaction`() {
        val lines = listOf(
            "cycle start: reason=\"x\"; stoppedFor=1234567ms, port 37215",
            "connect :41234 did not complete within 8000ms",
            "waited 300000ms; port=45678 adb_wifi_enabled read-back=0",
        )
        for (l in lines) assertEquals(l, LogRedaction.redact(l, "salt"))
    }

    @Test fun `once log says yes once per key and stays bounded`() {
        val once = OnceLog(maxKeys = 2)
        assertTrue(once.firstTime("a"))
        assertFalse(once.firstTime("a"))
        assertTrue(once.firstTime("b"))
        assertTrue(once.firstTime("c")) // evicts a
        assertTrue(once.firstTime("a"))
    }
}
