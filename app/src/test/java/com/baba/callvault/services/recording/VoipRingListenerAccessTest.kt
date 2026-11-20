/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.VoipRingListenerAccess.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review C19: the ring listener's approval follows "Record app calls", and only what we approved is undone. */
class VoipRingListenerAccessTest {

    /** A phone and a host in memory: the switch, the system's grant, and what the host was asked. */
    private class FakePort(
        var wantedNow: Boolean,
        override var approvedByUs: Boolean = false,
        var granted: Boolean = false,
        val hostConnects: Boolean = true,
        /** What the command does to the grant; false = the host answered but nothing changed. */
        val commandWorks: Boolean = true,
        /** The switch as it reads after the host wait (a toggle while waiting). */
        val wantedAfterWait: Boolean? = null,
    ) : VoipRingListenerAccess.Port {
        val asked = mutableListOf<Boolean>()
        private var waited = false
        override fun wanted() = if (waited && wantedAfterWait != null) wantedAfterWait else wantedNow
        override fun isGranted() = granted
        override fun awaitHost(): Boolean { waited = true; return hostConnects }
        override fun setAccess(allow: Boolean): String {
            asked += allow
            if (commandWorks) granted = allow
            return if (commandWorks) "exit=0 " else "exit=1 nope"
        }
    }

    @Test
    fun `turning app calls on approves the listener and remembers that we did`() {
        val port = FakePort(wantedNow = true)
        assertEquals(Outcome.APPROVED, VoipRingListenerAccess.syncWith(port))
        assertEquals(listOf(true), port.asked)
        assertTrue(port.approvedByUs)
    }

    @Test
    fun `turning app calls off removes the listener we approved`() {
        val port = FakePort(wantedNow = false, approvedByUs = true, granted = true)
        assertEquals(Outcome.REMOVED, VoipRingListenerAccess.syncWith(port))
        assertEquals(listOf(false), port.asked)
        assertFalse(port.approvedByUs)
        assertFalse(port.granted)
    }

    @Test
    fun `a listener we never approved is never removed`() {
        val port = FakePort(wantedNow = false, approvedByUs = false, granted = true)
        assertEquals(Outcome.NOTHING_TO_DO, VoipRingListenerAccess.syncWith(port))
        assertTrue(port.asked.isEmpty())
        assertTrue(port.granted)
    }

    @Test
    fun `a grant the user made themselves is not claimed as ours`() {
        val port = FakePort(wantedNow = true, approvedByUs = false, granted = true)
        assertEquals(Outcome.GRANTED_BY_USER, VoipRingListenerAccess.syncWith(port))
        assertTrue(port.asked.isEmpty())
        assertFalse(port.approvedByUs)
    }

    @Test
    fun `an approval that did not take is not recorded as ours`() {
        val port = FakePort(wantedNow = true, commandWorks = false)
        assertEquals(Outcome.FAILED, VoipRingListenerAccess.syncWith(port))
        assertFalse(port.approvedByUs)
    }

    @Test
    fun `a removal that did not take stays ours, so the next sync tries again`() {
        val port = FakePort(wantedNow = false, approvedByUs = true, granted = true, commandWorks = false)
        assertEquals(Outcome.FAILED, VoipRingListenerAccess.syncWith(port))
        assertTrue(port.approvedByUs)
    }

    @Test
    fun `no host leaves everything as it was`() {
        val port = FakePort(wantedNow = true, hostConnects = false)
        assertEquals(Outcome.NO_HOST, VoipRingListenerAccess.syncWith(port))
        assertTrue(port.asked.isEmpty())
        assertFalse(port.approvedByUs)
    }

    @Test
    fun `the switch is read again after the host wait`() {
        // On when the sync started, off by the time the host connected: nothing is approved.
        val port = FakePort(wantedNow = true, wantedAfterWait = false)
        assertEquals(Outcome.NOTHING_TO_DO, VoipRingListenerAccess.syncWith(port))
        assertTrue(port.asked.isEmpty())
        assertFalse(port.approvedByUs)
    }
}
