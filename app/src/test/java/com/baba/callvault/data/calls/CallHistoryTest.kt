/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

import com.baba.callvault.data.calls.CallHistory.Entry
import com.baba.callvault.data.calls.CallHistory.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHistoryTest {

    private val t = 1_791_545_452_000L

    /** A recording in the test: its name, start and whether it is a phone call. */
    private data class Rec(val name: String, val start: Long?, val phone: Boolean = true)

    private fun build(calls: List<PhoneCall>, recs: List<Rec>) =
        CallHistory.build(calls, recs, { it.start }, { it.phone })

    @Test
    fun `a phone call with a recording is one recorded row`() {
        val list = build(listOf(PhoneCall(t, 30, CallKind.INCOMING)), listOf(Rec("a", t + 2_000)))

        assertEquals(1, list.size)
        assertEquals("a", ((list[0] as Entry.Recorded<*>).recording as Rec).name)
    }

    @Test
    fun `an answered call with no recording is not recorded, a missed one is missed`() {
        val list = build(
            listOf(PhoneCall(t, 30, CallKind.OUTGOING), PhoneCall(t - 60_000, 0, CallKind.MISSED)),
            emptyList(),
        )

        assertEquals(1, CallHistory.filter(list, Filter.NOT_RECORDED).size)
        assertEquals(1, CallHistory.filter(list, Filter.MISSED).size)
        assertTrue(CallHistory.filter(list, Filter.RECORDED).isEmpty())
    }

    @Test
    fun `an app call recording is listed although Android never logged it`() {
        val list = build(emptyList(), listOf(Rec("whatsapp", t, phone = false)))

        assertEquals(1, CallHistory.filter(list, Filter.RECORDED).size)
    }

    @Test
    fun `an app call never takes the place of a phone call at the same minute`() {
        val list = build(listOf(PhoneCall(t, 30, CallKind.INCOMING)), listOf(Rec("whatsapp", t, phone = false)))

        assertEquals(2, list.size)
        assertEquals(1, CallHistory.filter(list, Filter.NOT_RECORDED).size)
    }

    @Test
    fun `one recording stands for one call only`() {
        val list = build(
            listOf(PhoneCall(t, 30, CallKind.INCOMING), PhoneCall(t + 30_000, 10, CallKind.OUTGOING)),
            listOf(Rec("only", t + 31_000)),
        )

        assertEquals(1, CallHistory.filter(list, Filter.RECORDED).size)
        assertEquals(1, CallHistory.filter(list, Filter.NOT_RECORDED).size)
    }

    @Test
    fun `the list is newest first`() {
        val list = build(
            listOf(PhoneCall(t - 100_000, 5, CallKind.MISSED)),
            listOf(Rec("new", t, phone = false)),
        )

        assertTrue(list[0] is Entry.Recorded<*>)
    }
}
