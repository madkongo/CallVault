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
    fun `a call that ended well before the recording began is not this call`() {
        // Replaces "a start just outside the fresh window": the window now allows ring time on the early side,
        // so a previous call is told apart by its end (DATE + DURATION), not its start.
        val call = PhoneCall(t - 60_000, 8, CallKind.OUTGOING, accountId = "other")
        assertNull(PhoneCallMatch.justEnded(listOf(call), t))
    }

    @Test
    fun `an incoming call that rang 30 seconds before it was answered and recorded is this call`() {
        // Call-log DATE is when it started ringing; the recording starts at the answer.
        val call = PhoneCall(t - 30_000, 120, CallKind.INCOMING, accountId = "sim2")
        assertEquals("sim2", PhoneCallMatch.justEnded(listOf(call), t)?.accountId)
    }

    @Test
    fun `an outgoing call recorded from the answer after a 45 second wait is this call`() {
        // Record-from-answer: DATE is the dial, the recording starts when the far end picks up.
        val call = PhoneCall(t - 45_000, 60, CallKind.OUTGOING, accountId = "sim1")
        assertEquals("sim1", PhoneCallMatch.justEnded(listOf(call), t)?.accountId)
    }

    @Test
    fun `with both rows present after a long ring the current call wins over the previous one`() {
        val previous = PhoneCall(t - 100_000, 20, CallKind.OUTGOING, accountId = "prev")
        val current = PhoneCall(t - 30_000, 90, CallKind.INCOMING, accountId = "cur")
        assertEquals("cur", PhoneCallMatch.justEnded(listOf(previous, current), t)?.accountId)
    }

    @Test
    fun `an unanswered outgoing call on the other SIM 30 seconds earlier is not this call on the early tries`() {
        // Re-review #A: this call's own row is not written yet.
        val previous = PhoneCall(t - 30_000, 0, CallKind.OUTGOING, accountId = "other")
        assertNull(PhoneCallMatch.justEnded(listOf(previous), t, allowRingTime = false))
    }

    @Test
    fun `once this call's row appears it wins over the unanswered previous call`() {
        val previous = PhoneCall(t - 30_000, 0, CallKind.OUTGOING, accountId = "other")
        val current = PhoneCall(t - 1_000, 40, CallKind.OUTGOING, accountId = "chosen")
        assertEquals("chosen", PhoneCallMatch.justEnded(listOf(previous, current), t, allowRingTime = false)?.accountId)
        assertEquals("chosen", PhoneCallMatch.justEnded(listOf(previous, current), t, allowRingTime = true)?.accountId)
    }

    @Test
    fun `a short answered call on the other SIM 30 seconds before is not this call on the early tries`() {
        val previous = PhoneCall(t - 30_000, 5, CallKind.INCOMING, accountId = "other")
        assertNull(PhoneCallMatch.justEnded(listOf(previous), t, allowRingTime = false))
        val current = PhoneCall(t - 2_000, 60, CallKind.INCOMING, accountId = "chosen")
        assertEquals("chosen", PhoneCallMatch.justEnded(listOf(previous, current), t, allowRingTime = true)?.accountId)
    }

    @Test
    fun `a row with a newer call after it is never the match`() {
        // The previous call is closer to the recording start than this call's (late-written, slightly later) row.
        val previous = PhoneCall(t - 10_000, 5, CallKind.OUTGOING, accountId = "other")
        val current = PhoneCall(t + 20_000, 30, CallKind.OUTGOING, accountId = "chosen")
        assertEquals("chosen", PhoneCallMatch.justEnded(listOf(previous, current), t, allowRingTime = false)?.accountId)
        assertEquals("chosen", PhoneCallMatch.justEnded(listOf(previous, current), t, allowRingTime = true)?.accountId)
    }

    @Test
    fun `ring time is still accepted when asked for`() {
        val call = PhoneCall(t - 30_000, 120, CallKind.INCOMING, accountId = "sim2")
        assertNull(PhoneCallMatch.justEnded(listOf(call), t, allowRingTime = false))
        assertEquals("sim2", PhoneCallMatch.justEnded(listOf(call), t, allowRingTime = true)?.accountId)
    }
}
