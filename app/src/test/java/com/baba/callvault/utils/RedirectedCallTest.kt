/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import com.baba.callvault.utils.RedirectedCall.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A call redirection app (e.g. Google Voice) places an outgoing call through a proxy number. The PHONE_STATE
 * broadcast carries the proxy, the call log the number that was dialled; the recording should be named after the latter.
 */
class RedirectedCallTest {

    private val sameNumber: (String, String) -> Boolean = { a, b -> a.takeLast(9) == b.takeLast(9) }
    private val recordingStart = 1_800_000_015_000L
    private val proxy = "+12025550100"
    private val dialled = "+12125550199"

    /** An entry is written when its call ends; by default, after this recording started. */
    private fun entry(date: Long, lastModified: Long = recordingStart + 60_000, number: String = dialled) =
        LogEntry(date, lastModified, number)

    @Test
    fun `a redirected call takes the dialled number from the log`() {
        val thisCall = RedirectedCall.entryForCall(listOf(entry(recordingStart - 2_000)), recordingStart)!!
        assertEquals(dialled, RedirectedCall.dialledNumber(thisCall, proxy, sameNumber))
    }

    @Test
    fun `a call that was not redirected keeps its number`() {
        assertNull(RedirectedCall.dialledNumber(entry(recordingStart - 2_000, number = "2025550100"), proxy, sameNumber))
    }

    @Test
    fun `a previous call whose entry was written before the recording started is not this call`() {
        // Unanswered call dialled 4 s earlier and hung up 1 s before this one started; this call's entry is not written yet.
        val previous = entry(recordingStart - 4_000, lastModified = recordingStart - 1_000, number = "+12125550111")
        assertNull(RedirectedCall.entryForCall(listOf(previous), recordingStart))
    }

    @Test
    fun `the latest entry wins once this call's entry is written`() {
        val previous = entry(recordingStart - 40_000, lastModified = recordingStart - 5_000, number = "+12125550111")
        val thisCall = entry(recordingStart - 1_000)
        assertEquals(thisCall, RedirectedCall.entryForCall(listOf(previous, thisCall), recordingStart))
    }

    @Test
    fun `an entry placed long before, or after the recording started, is not this call`() {
        val entries = listOf(
            entry(recordingStart - RedirectedCall.MAX_CALL_LEAD_MS - 1),
            entry(recordingStart + RedirectedCall.CLOCK_SLACK_MS + 1),
        )
        assertNull(RedirectedCall.entryForCall(entries, recordingStart))
    }

    @Test
    fun `a call recorded from answer after ringing for over a minute is this call`() {
        val rangLong = entry(recordingStart - 65_000)
        assertEquals(rangLong, RedirectedCall.entryForCall(listOf(rangLong), recordingStart))
    }

    @Test
    fun `at off-hook the dialled number replaces a proxy handle`() {
        assertEquals(dialled, RedirectedCall.numberAtOffhook(proxy, dialled, sameNumber))
    }

    @Test
    fun `at off-hook a handle that matches the dialled number is kept as the system gave it`() {
        assertEquals(proxy, RedirectedCall.numberAtOffhook(proxy, "2025550100", sameNumber))
    }

    @Test
    fun `at off-hook the dialled number fills a handle-less first broadcast`() {
        assertEquals(dialled, RedirectedCall.numberAtOffhook(null, dialled, sameNumber))
        assertEquals(dialled, RedirectedCall.numberAtOffhook("", dialled, sameNumber))
    }

    @Test
    fun `at off-hook without a dialled number nothing changes`() {
        assertEquals(proxy, RedirectedCall.numberAtOffhook(proxy, null, sameNumber))
        assertNull(RedirectedCall.numberAtOffhook(null, " ", sameNumber))
    }

    @Test
    fun `an entry without a number, or a call without a handle, changes nothing`() {
        assertNull(RedirectedCall.dialledNumber(entry(recordingStart - 2_000, number = ""), proxy, sameNumber))
        assertNull(RedirectedCall.dialledNumber(entry(recordingStart - 2_000), null, sameNumber))
    }
}
