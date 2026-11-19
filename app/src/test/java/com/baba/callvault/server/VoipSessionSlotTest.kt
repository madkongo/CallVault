/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Review C49 and its rework: `startVoipRecording` returns at once and the session is built on the worker. The app's
 * first answer-hold pause arrives on a binder thread in between and used to be dropped as "no VoIP session".
 */
class VoipSessionSlotTest {

    private class FakeSession {
        @Volatile var paused: Boolean? = null
    }

    private fun slot() = VoipSessionSlot<FakeSession> { s, p -> s.paused = p }

    @Test
    fun `a pause sent while the worker is still building the session is applied when it is published`() {
        val slot = slot()
        slot.beginStart()   // on the binder thread, before startVoipRecording returns
        val workerMayPublish = CountDownLatch(1)
        val session = FakeSession()
        val worker = Thread {
            workerMayPublish.await(5, TimeUnit.SECONDS)
            slot.publishThenStart(session) { /* start */ }
        }.apply { start() }
        // The app's pause arrives on another thread while the worker has not published yet.
        val result = arrayOfNulls<VoipSessionSlot.PauseResult>(1)
        Thread { result[0] = slot.setPaused(true) }.apply { start(); join(5_000) }
        assertEquals(VoipSessionSlot.PauseResult.QUEUED, result[0])
        workerMayPublish.countDown()
        worker.join(5_000)
        assertEquals(true, session.paused)
    }

    @Test
    fun `pause and publish racing freely always end with the pause applied`() {
        repeat(500) {
            val slot = slot()
            slot.beginStart()
            val session = FakeSession()
            val go = CountDownLatch(1)
            val a = Thread { go.await(); slot.publishThenStart(session) { } }.apply { start() }
            val b = Thread { go.await(); slot.setPaused(true) }.apply { start() }
            go.countDown()
            a.join(5_000); b.join(5_000)
            assertEquals("iteration $it", true, session.paused)
        }
    }

    @Test
    fun `a pause after publish goes straight to the session, and the last one wins`() {
        val slot = slot()
        slot.beginStart()
        assertEquals(VoipSessionSlot.PauseResult.QUEUED, slot.setPaused(true))
        assertEquals(VoipSessionSlot.PauseResult.QUEUED, slot.setPaused(false))
        val session = FakeSession()
        slot.publishThenStart(session) { }
        assertEquals(false, session.paused)
        assertEquals(VoipSessionSlot.PauseResult.APPLIED, slot.setPaused(true))
        assertEquals(true, session.paused)
    }

    @Test
    fun `a session that fails to start is withdrawn and a later pause is ignored`() {
        val slot = slot()
        slot.beginStart()
        slot.setPaused(true)
        val thrown = runCatching { slot.publishThenStart(FakeSession()) { throw IllegalStateException("no mic") } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertNull(slot.current)
        assertEquals(VoipSessionSlot.PauseResult.IGNORED, slot.setPaused(false))
    }

    @Test
    fun `with no start in flight a pause is ignored`() {
        assertEquals(VoipSessionSlot.PauseResult.IGNORED, slot().setPaused(true))
    }
}
