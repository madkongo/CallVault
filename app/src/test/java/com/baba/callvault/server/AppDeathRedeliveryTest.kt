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
}
