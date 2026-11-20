/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEndLogTest {

    /** Review N21b: the always-on file is one entry per line, so a line handed in with breaks stays one entry. */
    @Test
    fun `a line with breaks is written as one line`() {
        assertEquals("health ok capture recovery: sink=normal", CallEndLog.singleLine("health ok\ncapture recovery: sink=normal"))
        assertEquals("a b", CallEndLog.singleLine("a\r\nb"))
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val source = java.io.File(root, "com/baba/callvault/system/diagnostics/CallEndLog.kt").readText()
        assertTrue(source.contains("AppLogger.redactForReport(singleLine(line))"))
    }

    @Test
    fun `a name keeps its timestamp marker and extension but not the contact`() {
        assertEquals(
            "20261110_101010.000+0200_in.ogg",
            CallEndLog.safeName("20261110_101010.000+0200_in_Some Person.ogg"),
        )
        assertEquals(
            "20261110_101010.000+0200_voip-WhatsApp.m4a",
            CallEndLog.safeName("20261110_101010.000+0200_voip-WhatsApp_Some Person.m4a"),
        )
    }

    @Test
    fun `a contact-first name is cut down to the timestamp too`() {
        val safe = CallEndLog.safeName("Some Person_+972501234567_20261110_101010.000+0200.ogg")
        assertEquals("20261110_101010.000+0200.ogg", safe)
        assertFalse(safe.contains("Person"))
        assertFalse(safe.contains("972"))
    }

    @Test
    fun `a name with no timestamp leaks nothing`() {
        assertEquals("?.ogg", CallEndLog.safeName("Some Person.ogg"))
    }

    @Test
    fun `a failed catalog write names the exception and the elapsed time`() {
        val line = CallEndLog.catalogEnd(
            "20261110_101010.000+0200_in_Some Person.ogg", 11_700_000L, 4321L, IllegalStateException("db closed"),
        )
        assertTrue(line, line.contains("FAILED"))
        assertTrue(line, line.contains("IllegalStateException: db closed"))
        assertTrue(line, line.contains("elapsed=4321ms"))
        assertTrue(line, line.contains("size=11700000"))
        assertFalse(line, line.contains("Person"))
    }

    @Test
    fun `a good catalog write says done`() {
        val line = CallEndLog.catalogEnd("20261110_101010.000+0200_out.ogg", 10L, 12L, null)
        assertTrue(line, line.startsWith("catalog write done"))
    }

    @Test
    fun `a row probe is only logged above the limit`() {
        assertNull(CallEndLog.slowRowProbe("20261110_101010.000+0200_in.ogg", 1L, CallEndLog.SLOW_ROW_PROBE_MS, 0L))
        val slow = CallEndLog.slowRowProbe("20261110_101010.000+0200_in.ogg", 5L, CallEndLog.SLOW_ROW_PROBE_MS + 1, 0L)
        assertNotNull(slow)
        assertTrue(slow, slow!!.contains("length=5"))
    }

    @Test
    fun `the capture path is named for each way a call can be recorded`() {
        assertEquals("app-call mix (daemon)", CallEndLog.capturePathName(true, false, true, true))
        assertEquals("handoff (resilient)", CallEndLog.capturePathName(false, true, false, false))
        assertEquals("daemon direct AudioRecord", CallEndLog.capturePathName(false, false, true, true))
        assertEquals("daemon scrcpy (muxer)", CallEndLog.capturePathName(false, false, true, false))
        assertEquals("local scrcpy over ADB (muxer)", CallEndLog.capturePathName(false, false, false, false))
    }
}
