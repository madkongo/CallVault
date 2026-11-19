/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_ANSWERED
import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_NO_NOTIFICATION
import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_NO_TIMER
import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_RINGING
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review N17 (OP9, rf4, 2026-11-19): a WhatsApp call rang 22 s with "Start when they answer" on and was never
 * answered. "Recording started." showed at the first ring, the outcome said ENDED_BEFORE_CAPTURE, and 15 ms after
 * the outcome a new "held until the call is answered" line was written for a call that was already over.
 */
class VoipAnswerHoldLoopTest {

    /** Scripted host answers; [endsDuringPoll] = the call ends while that poll (1-based) is still in the binder. */
    private class FakeHost(private val answers: List<Int?>, private val endsDuringPoll: Int? = null) : VoipAnswerHoldLoop.Host {
        val events = mutableListOf<String>()
        var live = true
        private var polls = 0

        override fun isLive(): Boolean = live
        override fun poll(): Int? {
            polls++
            if (polls == endsDuringPoll) live = false
            if (polls > answers.size) { live = false; return answers.last() }
            return answers[polls - 1]
        }
        override fun ringAnswered(): Boolean = false
        override fun setHeld(hold: Boolean): Boolean {
            if (!live) return false
            events += "held:$hold"
            return true
        }
        override fun recordingFromNow(): Boolean {
            if (!live) return false
            events += "started"
            return true
        }
        override fun record(line: String) { events += "log:" + line.substringAfter("app call answer hold: ").substringBefore(" (") }
        override fun debug(line: String) = Unit
    }

    @Test
    fun `a poll that returns after the call ended arms no new hold and writes no held line`() = runTest {
        // The rf4 log: ringing (held), then the call ended while the next poll was in the binder; that poll came back
        // "no notification" (WhatsApp removes it at hang-up) and the old loop logged "held" again.
        val host = FakeHost(listOf(VOIP_RINGING, VOIP_NO_NOTIFICATION), endsDuringPoll = 2)
        VoipAnswerHoldLoop(host, normalMode = false, now = { 0L }).run()
        assertEquals(listOf("held:true", "log:held until the call is answered"), host.events)
    }

    @Test
    fun `a held call says recording started at the answer, not at the ring`() = runTest {
        val host = FakeHost(listOf(VOIP_RINGING, VOIP_RINGING, VOIP_ANSWERED))
        VoipAnswerHoldLoop(host, normalMode = false, now = { 0L }).run()
        assertEquals(
            listOf("held:true", "log:held until the call is answered", "held:false", "started", "log:released after 3 polls: call answered"),
            host.events,
        )
    }

    @Test
    fun `a call that is never held says recording started at once`() = runTest {
        val host = FakeHost(listOf(VOIP_NO_TIMER))
        VoipAnswerHoldLoop(host, normalMode = false, now = { 0L }).run()
        assertEquals(listOf("started", "log:not held: this app's notification shows no call timer"), host.events)
    }

    @Test
    fun `an unanswered call never says recording started`() = runTest {
        val host = FakeHost(listOf(VOIP_RINGING, VOIP_RINGING, VOIP_RINGING), endsDuringPoll = 3)
        VoipAnswerHoldLoop(host, normalMode = false, now = { 0L }).run()
        assertTrue(host.events.toString(), "started" !in host.events)
        assertEquals(1, host.events.count { it == "log:held until the call is answered" })
    }

    @Test
    fun `a call that ends while still held is discarded as unanswered, not as ended before capture`() {
        // rf4: 286 ms of audio before the hold, far side never heard, then 22 s of ringing. The outcome must say
        // UNANSWERED: ENDED_BEFORE_CAPTURE told the owner the capture failed to start.
        assertEquals(
            VoipOutcome.Reason.UNANSWERED,
            VoipAnswerHold.endDiscardReason(unanswered = true, wroteAnything = true, recordedMs = 286L, farHeard = false),
        )
        assertEquals(
            VoipOutcome.Reason.UNANSWERED,
            VoipAnswerHold.endDiscardReason(unanswered = true, wroteAnything = false, recordedMs = 0L, farHeard = false),
        )
        assertEquals(
            VoipOutcome.Reason.ENDED_BEFORE_CAPTURE,
            VoipAnswerHold.endDiscardReason(unanswered = false, wroteAnything = true, recordedMs = 286L, farHeard = false),
        )
        assertNull(VoipAnswerHold.endDiscardReason(unanswered = false, wroteAnything = true, recordedMs = 60_000L, farHeard = true))
    }

    @Test
    fun `the coordinator shows recording started at the start only when no hold applies`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val source = File(root, "com/baba/callvault/services/recording/VoipRecordingCoordinator.kt").readText()
        val calls = Regex("""showRecordingStarted\(\)""").findAll(source).count()
        assertEquals("showRecordingStarted() calls in the coordinator", 2, calls)
        assertTrue(source.contains("if (!holdApplies) RecordingNotificationHelper(context).showRecordingStarted()"))
    }
}
