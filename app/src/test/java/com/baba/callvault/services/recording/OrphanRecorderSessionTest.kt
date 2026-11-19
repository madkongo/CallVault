/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.health.CallLogEntry
import com.baba.callvault.services.recording.OrphanSessionPolicy.Action
import com.baba.callvault.services.recording.OrphanSessionPolicy.StartVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review C2 and N4, with the recorder faked the way the real one behaves: one session at a time, a start while
 * recording is ignored ("already recording"), and whatever it captures goes into the file of the session it is
 * running. The OP9 log of 2026-11-19 is the first scenario.
 */
class OrphanRecorderSessionTest {

    /** The daemon's latch, exactly: startRecording is ignored while a session runs. */
    private class FakeRecorder : OrphanRecorderSession.Recorder {
        var target: String? = null
        val finalised = mutableListOf<String>()
        val audio = mutableMapOf<String, MutableList<String>>()
        var stopWorks = true
        var stops = 0

        fun start(file: String) {
            if (target != null) return // "startRecording ignored: already recording"
            target = file
        }

        fun capture(what: String) {
            target?.let { audio.getOrPut(it) { mutableListOf() }.add(what) }
        }

        override fun isRecording(): Boolean = target != null
        override fun stopRecording() {
            stops++
            if (!stopWorks) return
            target?.let { finalised.add(it) }
            target = null
        }
    }

    private class FakeEnv(
        var owned: Boolean = false,
        var inCall: Boolean = false,
        var orphanStart: Long? = null,
        var lastNoCall: Long = 0L,
        var log: List<CallLogEntry> = emptyList(),
    ) : OrphanRecorderSession.Environment {
        var rescues = 0
        val lines = mutableListOf<String>()
        override fun ownedHere() = owned
        override fun callInProgress() = inCall
        override fun orphanStartedAt(exclude: File?) = orphanStart
        override fun lastNoCallAt() = lastNoCall
        override fun callLogAround(startedAt: Long) = log
        override fun scheduleRescue() { rescues++ }
        override fun record(line: String) { lines.add(line) }
    }

    private val call1Start = 1_000_000_000L

    @Test
    fun `N4 - after an app kill the second call is recorded into its own file, not the first call's`() {
        // Call 1 recorded; the phone kills CallVault 8 s in. The recorder keeps going past the hang-up.
        val recorder = FakeRecorder()
        recorder.start("call1")
        recorder.capture("call 1 audio")
        recorder.capture("silence after call 1 hung up")
        // A new CallVault process owns nothing. Call 2 starts; call 1 is in the call log (it ended).
        val env = FakeEnv(
            owned = false,
            inCall = true,
            orphanStart = call1Start,
            log = listOf(CallLogEntry(call1Start - 10_000L, 60, isIncoming = true, label = null)),
        )

        val outcome = OrphanRecorderSession.resolve(recorder, env, "call start", forCallStart = true)
        assertEquals(StartVerdict.START, outcome.startVerdict)
        recorder.start("call2")
        recorder.capture("call 2 audio")

        assertEquals(listOf("call1"), recorder.finalised)
        assertEquals(listOf("call 1 audio", "silence after call 1 hung up"), recorder.audio["call1"])
        assertEquals(listOf("call 2 audio"), recorder.audio["call2"])
        assertEquals("call2", recorder.target)
        assertEquals(1, env.rescues)
    }

    @Test
    fun `N4 - without the check the second call lands in the first call's file`() {
        // The bug as it was, against the same fake, so the test above is known to model it.
        val recorder = FakeRecorder()
        recorder.start("call1")
        recorder.start("call2")
        recorder.capture("call 2 audio")
        assertEquals(listOf("call 2 audio"), recorder.audio["call1"])
        assertNull(recorder.audio["call2"])
    }

    @Test
    fun `an orphan is stopped when the app restarts with no call in progress`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "app start")

        assertEquals(Action.STOP_ORPHAN, outcome.action)
        assertFalse(recorder.isRecording())
        assertEquals(1, env.rescues)
    }

    @Test
    fun `Q25 - a restart in the middle of a call keeps the live call's recording`() {
        // OnePlus killed and restarted CallVault mid-call: the session is that call's own recording.
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, lastNoCall = call1Start - 60_000L)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "app start")

        assertEquals(Action.KEEP_LIVE_CALL, outcome.action)
        assertEquals(StartVerdict.ADOPT, outcome.startVerdict)
        assertEquals(0, recorder.stops)
        assertTrue(recorder.isRecording())
    }

    @Test
    fun `Q25 - the kept session is stopped and rescued once that call ends`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start)
        OrphanRecorderSession.resolve(recorder, env, "app start")
        env.inCall = false
        env.lastNoCall = call1Start + 300_000L

        val outcome = OrphanRecorderSession.resolve(recorder, env, "phone call ended")

        assertEquals(Action.STOP_ORPHAN, outcome.action)
        assertEquals(listOf("call1"), recorder.finalised)
    }

    @Test
    fun `Q25 - at a restart mid-call the call log alone never cuts the live recording`() {
        // An earlier call in the log can look like the orphan's; with no new call starting, cutting would lose the rest.
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start,
            log = listOf(CallLogEntry(call1Start - 60_000L, 20, isIncoming = true, label = null)))

        val outcome = OrphanRecorderSession.resolve(recorder, env, "app start")

        assertEquals(Action.KEEP_LIVE_CALL, outcome.action)
        assertEquals(0, recorder.stops)
    }

    @Test
    fun `a session this process owns is never touched, even with no call up`() {
        // A VoIP recording plus a carrier IDLE must not stop the VoIP session.
        val recorder = FakeRecorder().apply { start("voip") }
        val env = FakeEnv(owned = true, inCall = false)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "phone call ended")

        assertEquals(Action.OWNED, outcome.action)
        assertEquals(0, recorder.stops)
        assertEquals(0, env.rescues)
    }

    @Test
    fun `a stop the recorder does not carry out is a failed start, never a reported success`() {
        val recorder = FakeRecorder().apply { start("call1"); stopWorks = false }
        val env = FakeEnv(inCall = true, orphanStart = call1Start,
            log = listOf(CallLogEntry(call1Start, 30, isIncoming = false, label = null)))

        val outcome = OrphanRecorderSession.resolve(recorder, env, "call start", forCallStart = true)

        assertEquals(StartVerdict.FAIL_BUSY, outcome.startVerdict)
        assertTrue(env.lines.any { "stop FAILED" in it })
    }

    @Test
    fun `an unanswerable recorder is left alone`() {
        val recorder = object : OrphanRecorderSession.Recorder {
            override fun isRecording(): Boolean? = null
            override fun stopRecording() = error("must not be asked")
        }
        assertEquals(Action.NONE, OrphanRecorderSession.resolve(recorder, FakeEnv(), "app start").action)
    }

    // ---- the evidence that the orphan's call is over ----

    @Test
    fun `a call-free moment after the orphan started proves its call ended`() {
        assertTrue(OrphanSessionPolicy.orphanCallEnded(call1Start, call1Start + 1, emptyList()))
        assertFalse(OrphanSessionPolicy.orphanCallEnded(call1Start, call1Start - 1, emptyList()))
    }

    @Test
    fun `a finished call-log entry for the orphan's call proves it ended`() {
        val ringing = CallLogEntry(call1Start - 20_000L, 5, isIncoming = true, label = null)
        assertTrue(OrphanSessionPolicy.orphanCallEnded(call1Start, 0L, listOf(ringing)))
    }

    @Test
    fun `an unrelated older call is not taken for the orphan's`() {
        val longBefore = CallLogEntry(call1Start - OrphanSessionPolicy.MAX_RING_BEFORE_RECORDING_MS - 1, 30, isIncoming = true, label = null)
        assertFalse(OrphanSessionPolicy.orphanCallEnded(call1Start, 0L, listOf(longBefore)))
    }

    @Test
    fun `with no note nothing is claimed about the orphan's call`() {
        assertFalse(OrphanSessionPolicy.orphanCallEnded(null, Long.MAX_VALUE, emptyList()))
    }

    @Test
    fun `decision matrix`() {
        assertEquals(Action.NONE, OrphanSessionPolicy.decide(false, false, true, false))
        assertEquals(Action.OWNED, OrphanSessionPolicy.decide(true, true, false, true))
        assertEquals(Action.STOP_ORPHAN, OrphanSessionPolicy.decide(true, false, false, false))
        assertEquals(Action.STOP_ORPHAN, OrphanSessionPolicy.decide(true, false, true, true))
        assertEquals(Action.KEEP_LIVE_CALL, OrphanSessionPolicy.decide(true, false, true, false))
    }
}
