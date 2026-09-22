/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import com.baba.callvault.services.call.AnswerWait.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Start when they answer": what the poll of the phone's precise call state decides on each tick.
 *
 * The recording of an outgoing call normally starts at OFFHOOK, which is the dial. With the option on,
 * the host is asked every [AnswerWait.POLL_MS] what the foreground call is doing, and the recording is
 * started at the first ACTIVE. Every other answer either keeps waiting or starts anyway; nothing here
 * can lose a call, because losing a call is worse than recording a few seconds of ringing.
 */
class AnswerWaitTest {

    @Test
    fun `the foreground call state is read out of the registry dump`() {
        // The host runs `dumpsys telephony.registry | grep mForegroundCallState`, so the value
        // arrives as that line — but be forgiving about what surrounds it.
        assertEquals(4, AnswerWait.parseState("mForegroundCallState=4"))
        assertEquals(1, AnswerWait.parseState("  mForegroundCallState=1\n"))
        assertEquals(0, AnswerWait.parseState("mCallState=2\nmRingingCallState=0\nmForegroundCallState=0\n"))
    }

    @Test
    fun `on a dual-SIM phone the SIM in the call is the one that counts`() {
        // The dump has one line per phone and the idle SIM says 0 for the whole call. The OP12
        // prints two such lines. Whichever line is answered wins; failing that, whichever is busy.
        assertEquals(AnswerWait.STATE_ACTIVE, AnswerWait.parseState("mForegroundCallState=0\nmForegroundCallState=1\n"))
        assertEquals(AnswerWait.STATE_ALERTING, AnswerWait.parseState("mForegroundCallState=4\nmForegroundCallState=0\n"))
        assertEquals(AnswerWait.STATE_ACTIVE, AnswerWait.parseState("mForegroundCallState=1\nmForegroundCallState=4\n"))
        assertEquals(AnswerWait.STATE_IDLE, AnswerWait.parseState("mForegroundCallState=0\nmForegroundCallState=0\n"))
    }

    @Test
    fun `no readable state is null`() {
        assertNull(AnswerWait.parseState(null))
        assertNull(AnswerWait.parseState(""))
        assertNull(AnswerWait.parseState("(refused)"))
        assertNull(AnswerWait.parseState("mForegroundCallState="))
    }

    @Test
    fun `an active call starts the recording`() {
        assertEquals(Decision.START, AnswerWait.decide(state = AnswerWait.STATE_ACTIVE, elapsedMs = 4_000))
    }

    @Test
    fun `a call put on hold was answered`() {
        // HOLDING can only follow ACTIVE; if the poll missed the active tick, this is still an answer.
        assertEquals(Decision.START, AnswerWait.decide(state = AnswerWait.STATE_HOLDING, elapsedMs = 4_000))
    }

    @Test
    fun `dialling and ringing keep waiting`() {
        assertEquals(Decision.WAIT, AnswerWait.decide(state = AnswerWait.STATE_DIALING, elapsedMs = 0))
        assertEquals(Decision.WAIT, AnswerWait.decide(state = AnswerWait.STATE_ALERTING, elapsedMs = 20_000))
    }

    @Test
    fun `an idle registry keeps waiting too`() {
        // At the OFFHOOK broadcast the registry can still say IDLE for a tick, and the call's end is
        // reported by the IDLE broadcast, which cancels the wait — so a 0 here is never a reason to
        // give up on its own.
        assertEquals(Decision.WAIT, AnswerWait.decide(state = 0, elapsedMs = 300))
        assertEquals(Decision.WAIT, AnswerWait.decide(state = 0, elapsedMs = 30_000))
    }

    @Test
    fun `no signal at all starts the recording rather than waiting for one`() {
        // An older host that refuses the dump, a host that is not connected, a ROM whose dump lacks
        // the line: none of these may cost the call. Without a signal the option is simply off.
        assertEquals(Decision.START, AnswerWait.decide(state = null, elapsedMs = 0))
    }

    @Test
    fun `a host that is not connected yet is given a moment before the option is given up`() {
        // Standby asks for the daemon at the dial; the binder can arrive a second or two later. A
        // null read from no host is "not yet", not "no signal" — but only for a few seconds, after
        // which it is the same as any other missing signal and the call is recorded from there.
        assertEquals(Decision.WAIT, AnswerWait.decide(state = null, elapsedMs = 0, hostConnected = false))
        assertEquals(Decision.WAIT, AnswerWait.decide(state = null, elapsedMs = AnswerWait.HOST_GRACE_MS - 1, hostConnected = false))
        assertEquals(Decision.START, AnswerWait.decide(state = null, elapsedMs = AnswerWait.HOST_GRACE_MS, hostConnected = false))
        // A connected host that returns nothing readable gets no grace: it has answered, and the
        // answer is that this ROM's dump has no such line.
        assertEquals(Decision.START, AnswerWait.decide(state = null, elapsedMs = 0, hostConnected = true))
    }

    @Test
    fun `waiting has a ceiling`() {
        // A poll that never sees ACTIVE — a ROM with a different state machine, say — must not wait
        // for the whole call. Past the ceiling the recording starts whatever the state says.
        assertEquals(Decision.WAIT, AnswerWait.decide(state = AnswerWait.STATE_ALERTING, elapsedMs = AnswerWait.MAX_WAIT_MS - 1))
        assertEquals(Decision.START, AnswerWait.decide(state = AnswerWait.STATE_ALERTING, elapsedMs = AnswerWait.MAX_WAIT_MS))
    }
}
