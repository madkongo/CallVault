/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneCallMatchTest {

    private val t = 1_791_545_452_000L

    @Test
    fun `the nearest answered call within two minutes is the match`() {
        val calls = listOf(
            PhoneCall(t - 90_000, 30, CallKind.OUTGOING, accountId = "far"),
            PhoneCall(t + 3_000, 12, CallKind.INCOMING, accountId = "near"),
        )
        assertEquals("near", PhoneCallMatch.nearest(calls, t)?.accountId)
    }

    @Test
    fun `a missed call is never the call that was recorded`() {
        assertNull(PhoneCallMatch.nearest(listOf(PhoneCall(t, 0, CallKind.MISSED)), t))
    }

    @Test
    fun `nothing within two minutes is no match`() {
        assertNull(PhoneCallMatch.nearest(listOf(PhoneCall(t - 300_000, 5, CallKind.INCOMING)), t))
    }

    @Test
    fun `a call one minute earlier is the two-minute match and not the call that just ended`() {
        // realme 12 Pro, 2026-11-18: the previous call was still inside two minutes, and the new
        // call-log row was not written yet, so the recording kept the previous call's SIM.
        val previous = PhoneCall(t - 60_000, 10, CallKind.OUTGOING, accountId = "treetopia")
        assertEquals("treetopia", PhoneCallMatch.nearest(listOf(previous), t)?.accountId)
        assertNull(PhoneCallMatch.justEnded(listOf(previous), t))
    }

    @Test
    fun `once both rows exist the call that just ended is the close one`() {
        val previous = PhoneCall(t - 60_000, 10, CallKind.OUTGOING, accountId = "treetopia")
        val current = PhoneCall(t + 2_000, 10, CallKind.OUTGOING, accountId = "sim1")
        val calls = listOf(previous, current)
        assertEquals("sim1", PhoneCallMatch.justEnded(calls, t)?.accountId)
        assertEquals("sim1", PhoneCallMatch.nearest(calls, t)?.accountId)
    }

    @Test
    fun `a start twenty seconds off is still this call`() {
        val call = PhoneCall(t - 20_000, 8, CallKind.OUTGOING, accountId = "sim1")
        assertEquals("sim1", PhoneCallMatch.justEnded(listOf(call), t)?.accountId)
    }

    @Test
    fun `a start just outside the fresh window is not this call`() {
        val call = PhoneCall(t - 26_000, 8, CallKind.OUTGOING, accountId = "other")
        assertNull(PhoneCallMatch.justEnded(listOf(call), t))
    }
}
