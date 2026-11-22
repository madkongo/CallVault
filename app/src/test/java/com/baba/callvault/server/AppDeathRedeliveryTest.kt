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

class AppDeathRedeliveryTest {

    @Test
    fun `while recording, the first re-delivery comes after a short wait`() {
        assertEquals(1_000L, AppDeathRedelivery.nextDelayMs(attempt = 0, recording = true, clientSeen = false))
    }

    @Test
    fun `quick retries come first, then a periodic gap, then the attempts run out`() {
        assertEquals(2_000L, AppDeathRedelivery.nextDelayMs(1, true, false))
        assertEquals(2_000L, AppDeathRedelivery.nextDelayMs(2, true, false))
        assertEquals(AppDeathRedelivery.PERIODIC_GAP_MS, AppDeathRedelivery.nextDelayMs(3, true, false))
        assertEquals(AppDeathRedelivery.PERIODIC_GAP_MS, AppDeathRedelivery.nextDelayMs(AppDeathRedelivery.MAX_ATTEMPTS - 1, true, false))
        assertNull(AppDeathRedelivery.nextDelayMs(AppDeathRedelivery.MAX_ATTEMPTS, true, false))
    }

    @Test
    fun `an app call on the binder ends the retries, a provider reply alone does not`() {
        assertNull(AppDeathRedelivery.nextDelayMs(1, true, clientSeen = true))
        assertEquals(2_000L, AppDeathRedelivery.nextDelayMs(1, true, clientSeen = false))
    }

    @Test
    fun `an idle daemon never restarts a stopped app`() {
        assertNull(AppDeathRedelivery.nextDelayMs(0, recording = false, clientSeen = false))
    }

    @Test
    fun `recording that ends between retries stops them`() {
        assertNull(AppDeathRedelivery.nextDelayMs(1, recording = false, clientSeen = false))
    }

    @Test
    fun `app restarts per recording are capped, and a tight loop does not start a new cycle`() {
        assertTrue(AppDeathRedelivery.mayStartCycle(0, null))
        assertTrue(AppDeathRedelivery.mayStartCycle(2, AppDeathRedelivery.MIN_CYCLE_GAP_MS))
        assertFalse(AppDeathRedelivery.mayStartCycle(AppDeathRedelivery.MAX_CYCLES, null))
        assertFalse(AppDeathRedelivery.mayStartCycle(1, AppDeathRedelivery.MIN_CYCLE_GAP_MS - 1))
    }

    @Test
    fun `only the uid-active transaction counts as the app becoming active`() {
        assertTrue(AppDeathRedelivery.isActiveTransaction(code = 2, activeCode = 2))
        assertFalse(AppDeathRedelivery.isActiveTransaction(code = 1, activeCode = 2))
        assertTrue("code unknown: fall back to the old behaviour", AppDeathRedelivery.isActiveTransaction(code = 7, activeCode = null))
    }

    @Test
    fun `a force-stopped app is never reopened, an unreadable state is not treated as stopped`() {
        assertFalse(AppDeathRedelivery.mayDeliver(packageStopped = true))
        assertTrue(AppDeathRedelivery.mayDeliver(packageStopped = false))
        assertTrue(AppDeathRedelivery.mayDeliver(packageStopped = null))
    }

    private fun poll(w: OrphanSelfStop, t: Long, recording: Boolean = true, attached: Boolean = false, callUp: Boolean? = false) =
        w.onPoll(t, recording, attached, callUp)

    @Test
    fun `the daemon stops itself only after the call has been over for the quiet time`() {
        val w = OrphanSelfStop(quietMs = 5_000)
        assertEquals(OrphanSelfStop.Decision.WAIT, poll(w, 0))
        assertEquals(OrphanSelfStop.Decision.WAIT, poll(w, 4_999))
        assertEquals(OrphanSelfStop.Decision.STOP, poll(w, 5_000))
    }

    @Test
    fun `a call audio mode coming back restarts the quiet time`() {
        val w = OrphanSelfStop(quietMs = 5_000)
        poll(w, 0)
        assertEquals(OrphanSelfStop.Decision.WAIT, poll(w, 3_000, callUp = true))
        assertEquals(OrphanSelfStop.Decision.WAIT, poll(w, 6_000))
        assertEquals(OrphanSelfStop.Decision.STOP, poll(w, 11_000))
    }

    @Test
    fun `never stops while a call is up`() {
        val w = OrphanSelfStop(quietMs = 5_000)
        assertEquals(OrphanSelfStop.Decision.WAIT, poll(w, 0, callUp = true))
        assertEquals(OrphanSelfStop.Decision.WAIT, poll(w, 60_000, callUp = true))
    }

    @Test
    fun `an unreadable call state never stops the recording and resets the quiet time`() {
        val w = OrphanSelfStop(quietMs = 5_000)
        poll(w, 0)
        assertEquals(OrphanSelfStop.Decision.UNREADABLE, poll(w, 4_000, callUp = null))
        assertEquals(OrphanSelfStop.Decision.WAIT, poll(w, 6_000))
        assertEquals(OrphanSelfStop.Decision.STOP, poll(w, 11_000))
    }

    @Test
    fun `an attached app or no recording means nothing to do`() {
        val w = OrphanSelfStop(quietMs = 5_000)
        assertEquals(OrphanSelfStop.Decision.IDLE, poll(w, 0, attached = true))
        assertEquals(OrphanSelfStop.Decision.IDLE, poll(w, 9_000, recording = false))
    }

    @Test
    fun `audio modes ringtone to redirect count as a call, normal does not`() {
        assertTrue((1..6).all(OrphanSelfStop.Companion::isCallAudioMode))
        assertFalse(OrphanSelfStop.isCallAudioMode(0))
    }

    @Test
    fun `the quiet time outlasts the app's own wait for a young VoIP call and stays under the rescue`() {
        assertTrue(OrphanSelfStop.QUIET_MS >= 35_000L)
        assertTrue(OrphanSelfStop.QUIET_MS < 90_000L)
    }

    @Test
    fun `an active VoIP playback with a normal audio mode keeps the call up, either unreadable read blocks a stop`() {
        assertEquals(true, OrphanSelfStop.callUp(modeIsCall = false, voiceCommActive = true))
        assertEquals(true, OrphanSelfStop.callUp(modeIsCall = true, voiceCommActive = null))
        assertEquals(null, OrphanSelfStop.callUp(modeIsCall = false, voiceCommActive = null))
        assertEquals(null, OrphanSelfStop.callUp(modeIsCall = null, voiceCommActive = false))
        assertEquals(false, OrphanSelfStop.callUp(modeIsCall = false, voiceCommActive = false))
    }

    @Test
    fun `the restart cap is per recording session`() {
        val cycles = RestartCycles()
        var t = 0L
        repeat(AppDeathRedelivery.MAX_CYCLES) { assertTrue(cycles.tryStart(t)); t += AppDeathRedelivery.MIN_CYCLE_GAP_MS }
        assertFalse(cycles.tryStart(t))
        cycles.onSessionStarted()
        assertEquals(0, cycles.count())
        assertTrue(cycles.tryStart(t))
    }

    private fun watch(mayRestart: Boolean = true) = OrphanWatch(OrphanSelfStop(quietMs = 5_000), mayRestart, diedAt = 0)

    private fun OrphanWatch.tick(
        now: Long, current: Boolean = true, recording: Boolean = true, clientSeen: Boolean = false,
        callUp: Boolean? = false, voice: Boolean? = false, stopped: Boolean? = false, holds: Boolean = clientSeen,
    ) = step(now, current, recording, clientHolds = holds, clientOwns = clientSeen, callUp, voice) { stopped }

    @Test
    fun `an app that is calling again finishes the watch before any self-stop`() {
        val w = watch(mayRestart = false)
        w.tick(0)
        assertEquals(OrphanWatch.Action.APP_RETURNED, w.tick(6_000, clientSeen = true).action)
    }

    @Test
    fun `a newer death supersedes this watch`() {
        assertEquals(OrphanWatch.Action.SUPERSEDED, watch().tick(0, current = false).action)
    }

    @Test
    fun `the watch self-stops after the quiet time with no delivery in the way`() {
        val w = watch(mayRestart = false)
        assertEquals(OrphanWatch.Action.NONE, w.tick(0).action)
        assertEquals(OrphanWatch.Action.SELF_STOP, w.tick(5_000).action)
    }

    @Test
    fun `a force-stopped app gets no delivery while the self-stop still runs`() {
        val w = watch()
        val first = w.tick(1_000, stopped = true)
        assertEquals(OrphanWatch.Action.NONE, first.action)
        assertTrue(first.appForceStopped)
        assertEquals(OrphanWatch.Action.NONE, w.tick(4_000, stopped = true).action)
        assertEquals(OrphanWatch.Action.SELF_STOP, w.tick(6_000, stopped = true).action)
    }

    @Test
    fun `an app that is not stopped is offered the binder when due, then on schedule`() {
        val w = watch()
        assertEquals(OrphanWatch.Action.NONE, w.tick(999, callUp = true).action)
        assertEquals(OrphanWatch.Action.DELIVER, w.tick(1_000, callUp = true).action)
        w.onDelivered(1_000)
        assertEquals(OrphanWatch.Action.NONE, w.tick(2_999, callUp = true).action)
        assertEquals(OrphanWatch.Action.DELIVER, w.tick(3_000, callUp = true).action)
    }

    @Test
    fun `an unreadable stopped state delivers at most once`() {
        val w = watch()
        assertEquals(OrphanWatch.Action.DELIVER, w.tick(1_000, callUp = true, stopped = null).action)
        w.onDelivered(1_000)
        val second = w.tick(3_000, callUp = true, stopped = null)
        assertEquals(OrphanWatch.Action.NONE, second.action)
        assertTrue(second.stoppedUnknown)
    }

    @Test
    fun `an unreadable audio state is reported and never stops`() {
        val w = watch(mayRestart = false)
        val s = w.tick(60_000, callUp = null)
        assertEquals(OrphanWatch.Action.NONE, s.action)
        assertTrue(s.audioUnreadable)
    }

    @Test
    fun `an app that only holds the binder does not disable the self-stop`() {
        val w = watch(mayRestart = false)
        assertEquals(OrphanWatch.Action.NONE, w.tick(0, holds = true).action)
        assertEquals(OrphanWatch.Action.SELF_STOP, w.tick(5_000, holds = true).action)
    }

    @Test
    fun `an app that only holds the binder stops the re-delivery`() {
        val w = watch()
        assertEquals(OrphanWatch.Action.NONE, w.tick(1_000, callUp = true, holds = true).action)
        assertEquals(OrphanWatch.Action.NONE, w.tick(20_000, callUp = true, holds = true).action)
    }

    @Test
    fun `an app that claims the session finishes the watch`() {
        val w = watch(mayRestart = false)
        assertEquals(OrphanWatch.Action.APP_RETURNED, w.tick(0, clientSeen = true).action)
    }

    @Test
    fun `client signals keep holding and owning apart and per death`() {
        val s = ClientSignals()
        assertFalse(s.holdsSince(0)); assertFalse(s.ownsSince(0))
        s.noteHold(100)
        assertTrue(s.holdsSince(100)); assertFalse(s.ownsSince(100))
        assertFalse(s.holdsSince(101)) // a later death resets what counts
        s.noteOwner(200)
        assertTrue(s.ownsSince(150)); assertTrue(s.holdsSince(150)) // owning is also holding
        assertFalse(s.ownsSince(201))
    }
}
