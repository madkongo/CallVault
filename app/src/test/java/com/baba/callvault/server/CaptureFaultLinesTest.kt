/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review C38: a capture loop that stops getting audio must say so, once, without changing what the loop does. */
class CaptureFaultLinesTest {

    @Test
    fun a_clean_capture_has_no_summary() {
        val tally = ReadFaultTally()
        repeat(1_000) { assertFalse(tally.onRead(4096)) }
        assertNull(tally.summary())
    }

    @Test
    fun the_first_error_is_reported_to_the_caller_once() {
        val tally = ReadFaultTally()
        assertTrue(tally.onRead(-6))
        assertFalse(tally.onRead(-6))
        assertFalse(tally.onRead(-3))
        assertEquals(-6, tally.firstCode)
        assertEquals(3L, tally.errors)
    }

    @Test
    fun the_summary_names_the_first_code_and_the_longest_run() {
        val tally = ReadFaultTally()
        tally.onRead(4096)
        tally.onRead(-6); tally.onRead(-6); tally.onRead(-6)
        tally.onRead(4096)
        tally.onRead(-3)
        val line = tally.summary()!!
        assertTrue(line, "readErrors=4" in line)
        assertTrue(line, "firstCode=-6(DEAD_OBJECT)" in line)
        assertTrue(line, "longestRun=3" in line)
    }

    @Test
    fun a_good_read_ends_a_run() {
        val tally = ReadFaultTally()
        tally.onRead(-1); tally.onRead(-1)
        tally.onRead(100)
        tally.onRead(-1)
        assertEquals(2L, tally.longestRun)
    }

    @Test
    fun a_few_empty_reads_are_not_a_fault_but_a_flood_is() {
        val few = ReadFaultTally()
        repeat(5) { few.onRead(0) }
        assertNull(few.summary())

        val flood = ReadFaultTally()
        repeat(ReadFaultTally.EMPTY_READS_WORTH_SAYING.toInt()) { flood.onRead(0) }
        assertTrue(flood.summary()!!.contains("emptyReads=100"))
        assertEquals("an empty read is never the 'first error' that gets logged", 0L, flood.errors)
    }

    @Test
    fun an_empty_read_alone_does_not_trigger_the_first_error_log() {
        assertFalse(ReadFaultTally().onRead(0))
    }

    @Test
    fun an_unknown_code_is_named_by_its_number() {
        assertEquals("code -99", CaptureFaultLines.codeName(-99))
    }

    @Test
    fun a_voip_side_that_stopped_says_which_side_and_when() {
        val line = CaptureFaultLines.voipSideEnded("near", -6, 41_200L)
        assertTrue(line, "near" in line && "DEAD_OBJECT" in line && "41200ms" in line)
    }

    @Test
    fun a_failed_thread_without_a_message_still_says_something() {
        assertTrue(CaptureFaultLines.threadFailed("encode", null).contains("no message"))
        assertTrue(CaptureFaultLines.threadFailed("encode", "codec died").contains("codec died"))
    }
}
