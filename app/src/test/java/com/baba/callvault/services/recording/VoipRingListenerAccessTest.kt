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
        override fun awaitHost(keepWaiting: () -> Boolean): Boolean {
            waited = true
            if (!keepWaiting()) return false
            return hostConnects
        }
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

    @Test
    fun `a sync superseded by a newer request stops waiting and changes nothing`() {
        val port = FakePort(wantedNow = true, hostConnects = false)
        assertEquals(Outcome.SUPERSEDED, VoipRingListenerAccess.syncWith(port, superseded = { true }))
        assertTrue(port.asked.isEmpty())
        assertFalse(port.approvedByUs)
    }

    /** Review of C19: a toggle storm while the host is down must not pile up minutes-long waits. */
    @Test
    fun `requests while one is queued collapse into that one`() {
        val tasks = ArrayDeque<Runnable>()
        var runs = 0
        val syncer = VoipRingListenerAccess.CoalescingSyncer({ tasks.addLast(it) }) { runs++; Outcome.APPROVED }
        repeat(10) { syncer.request() }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(1, runs)
    }

    @Test
    fun `a request made while a sync is waiting supersedes it and queues exactly one more`() {
        val tasks = ArrayDeque<Runnable>()
        var sawSuperseded: Boolean? = null
        lateinit var syncer: VoipRingListenerAccess.CoalescingSyncer
        syncer = VoipRingListenerAccess.CoalescingSyncer({ tasks.addLast(it) }) { superseded ->
            // While this sync waits for the host, the user toggles three more times.
            if (sawSuperseded == null) {
                repeat(3) { syncer.request() }
                sawSuperseded = superseded()
                Outcome.SUPERSEDED
            } else Outcome.APPROVED
        }
        syncer.request()
        tasks.removeFirst().run()
        assertEquals(true, sawSuperseded)
        assertEquals(1, tasks.size)
    }

    /** Review of C19: a sync that could not reach the host is retried once the host connects, and only then. */
    private fun syncerReturning(vararg outcomes: Outcome?): Pair<VoipRingListenerAccess.CoalescingSyncer, ArrayDeque<Runnable>> {
        val tasks = ArrayDeque<Runnable>()
        val queue = ArrayDeque(outcomes.toList())
        val syncer = VoipRingListenerAccess.CoalescingSyncer({ tasks.addLast(it) }) { queue.removeFirst() }
        return syncer to tasks
    }

    @Test
    fun `a sync that found no host is retried once when the host connects`() {
        val (syncer, tasks) = syncerReturning(Outcome.NO_HOST, Outcome.APPROVED)
        syncer.request()
        tasks.removeFirst().run()
        syncer.onHostConnected()
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        // Done now: a later reconnect queues nothing.
        syncer.onHostConnected()
        assertEquals(0, tasks.size)
    }

    @Test
    fun `a sync that threw (host died mid-call) is retried when the host connects`() {
        val (syncer, tasks) = syncerReturning(null, Outcome.REMOVED)
        syncer.request()
        tasks.removeFirst().run()
        syncer.onHostConnected()
        assertEquals(1, tasks.size)
    }

    @Test
    fun `a completed sync is not retried on reconnect`() {
        for (done in listOf(Outcome.APPROVED, Outcome.REMOVED, Outcome.NOTHING_TO_DO, Outcome.GRANTED_BY_USER, Outcome.FAILED)) {
            val (syncer, tasks) = syncerReturning(done)
            syncer.request()
            tasks.removeFirst().run()
            syncer.onHostConnected()
            assertEquals(done.name, 0, tasks.size)
        }
    }

    @Test
    fun `repeated reconnects after one failure queue one retry, not a loop`() {
        val (syncer, tasks) = syncerReturning(Outcome.NO_HOST, Outcome.NO_HOST)
        syncer.request()
        tasks.removeFirst().run()
        repeat(3) { syncer.onHostConnected() }
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()  // the host went away again before the retry reached it
        assertEquals(0, tasks.size)
    }
}
