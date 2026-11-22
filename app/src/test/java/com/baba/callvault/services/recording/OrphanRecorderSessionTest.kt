/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.server.DaemonSessionOwnership
import com.baba.callvault.services.recording.OrphanSessionPolicy.Action
import com.baba.callvault.services.recording.OrphanSessionPolicy.StartVerdict
import org.junit.After
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
        var inCall: Boolean? = false,
        var orphanStart: Long? = null,
        var lastNoCall: Long = 0L,
        var liveCallBegan: Long? = null,
    ) : OrphanRecorderSession.Environment {
        var rescues = 0
        var noSessionMarks = 0
        val clearedKeeping = mutableListOf<File?>()
        val lines = mutableListOf<String>()
        override fun ownedHere() = owned
        override fun callInProgress() = inCall
        override fun orphanStartedAt(exclude: File?) = orphanStart
        override fun lastNoCallAt() = lastNoCall
        override fun liveCallBeganAt() = liveCallBegan
        override fun markNoCall() { lastNoCall = System.currentTimeMillis(); liveCallBegan = null }
        override fun markNoSession() { noSessionMarks++ }
        override fun clearSessionRecord(keep: File?) { clearedKeeping.add(keep) }
        override fun scheduleRescue() { rescues++ }
        var stagingFile: File? = null
        val rescuedNow = mutableListOf<File>()
        override fun orphanStagingFile(exclude: File?): File? = stagingFile
        override fun scheduleRescueNow(file: File) { rescuedNow += file }
        var laterCall: Boolean? = false
        var appCall = false
        override fun orphanWasAppCall() = appCall
        var laterCallFn: ((Long) -> Boolean?)? = null
        override fun callLoggedAfter(sinceMillis: Long): Boolean? = laterCallFn?.invoke(sinceMillis) ?: laterCall
        val marks = mutableListOf<Pair<File, Boolean>>()
        val ranOnMarks = mutableListOf<Int>()
        val appCallMarks = mutableListOf<Boolean>()
        var markWorks = true
        var callEnded: Long? = null
        var finalised: OrphanRecorderSession.DaemonFinalised? = null
        var selfStopAt: Long? = null
        override fun daemonSelfStopAt(): Long? = selfStopAt
        override fun daemonFinalisedOrphan(exclude: File?) = finalised
        var nowMs: Long = 0L
        override fun callEndedAt(): Long? = callEnded
        override fun now(): Long = nowMs
        override fun markOrphanStopped(file: File, callEnded: Boolean, ranOnSeconds: Int, appCall: Boolean): Boolean {
            marks += file to callEnded; ranOnMarks += ranOnSeconds; appCallMarks += appCall; return markWorks
        }
        override fun record(line: String) { lines.add(line) }
    }

    private val call1Start = 1_000_000_000L

    @After
    fun tearDown() = DaemonSessionOwnership.clearForTest()

    @Test
    fun `N4 - after an app kill the second call is recorded into its own file, not the first call's`() {
        // Call 1 recorded; the phone kills CallVault 8 s in. The recorder keeps going past the hang-up.
        val recorder = FakeRecorder()
        recorder.start("call1")
        recorder.capture("call 1 audio")
        recorder.capture("silence after call 1 hung up")
        // Call B rings 5 minutes later and cold-starts the app: a call is up, nothing proves call 1 ended except
        // that call B began long after call 1's recording did.
        val env = FakeEnv(inCall = true, orphanStart = call1Start, liveCallBegan = call1Start + 300_000L)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "call start")
        assertEquals(StartVerdict.START, OrphanSessionPolicy.forStart(outcome.action, outcome.stillRecording))
        recorder.start("callB")
        recorder.capture("call B audio")

        assertEquals(listOf("call1"), recorder.finalised)
        assertEquals(listOf("call 1 audio", "silence after call 1 hung up"), recorder.audio["call1"])
        assertEquals(listOf("call B audio"), recorder.audio["callB"])
        assertEquals(1, env.rescues)
    }

    @Test
    fun `N4 - an app-call orphan with no call log and no call-free moment is still stopped for the next call`() {
        // Second review, HIGH 1: the case the call log could never prove (an app call, or no READ_CALL_LOG).
        val recorder = FakeRecorder().apply { start("whatsapp1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, lastNoCall = 0L, liveCallBegan = call1Start + 90_000L)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "app call start")

        assertEquals(Action.STOP_ORPHAN, outcome.action)
        assertEquals(listOf("whatsapp1"), recorder.finalised)
    }

    @Test
    fun `a stopped orphan clears the session record, sparing the live session's file`() {
        val recorder = FakeRecorder().apply { start("whatsapp1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, lastNoCall = 0L, liveCallBegan = call1Start + 90_000L)
        val live = File("/staging/live.ogg")

        OrphanRecorderSession.resolve(recorder, env, "app call start", exclude = live)

        assertEquals(listOf<File?>(live), env.clearedKeeping)
    }

    @Test
    fun `N4 - without the check the second call lands in the first call's file`() {
        // The bug as it was, against the same fake, so the tests above are known to model it.
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
    fun `Q25 - a restart mid-call keeps the live call's recording, even with an earlier finished call`() {
        // Second review, HIGH 2: the OnePlus restart mid-call, where the restarted app treats the call as a fresh one.
        // The call began (as the killed process noted) before its recording started; an earlier finished call no
        // longer matters, because the call log no longer decides anything while a call is up.
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, lastNoCall = call1Start - 120_000L, liveCallBegan = call1Start - 8_000L)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "call start")

        assertEquals(Action.KEEP_LIVE_CALL, outcome.action)
        assertEquals(StartVerdict.ADOPT, OrphanSessionPolicy.forStart(outcome.action, outcome.stillRecording))
        assertEquals(0, recorder.stops)
    }

    @Test
    fun `Q25 - with no record of when the live call began, the session is kept`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, liveCallBegan = null)
        assertEquals(Action.KEEP_LIVE_CALL, OrphanRecorderSession.resolve(recorder, env, "app start").action)
        assertEquals(0, recorder.stops)
    }

    @Test
    fun `the same-call margin - an orphan started just before the call began is kept, one clearly before is stopped`() {
        val began = call1Start + 100_000L
        assertFalse(OrphanSessionPolicy.orphanPredatesLiveCall(began - OrphanSessionPolicy.SAME_CALL_MARGIN_MS, began))
        assertFalse(OrphanSessionPolicy.orphanPredatesLiveCall(began + 5_000L, began))
        assertTrue(OrphanSessionPolicy.orphanPredatesLiveCall(began - OrphanSessionPolicy.SAME_CALL_MARGIN_MS - 1, began))
        assertFalse(OrphanSessionPolicy.orphanPredatesLiveCall(null, began))
        assertFalse(OrphanSessionPolicy.orphanPredatesLiveCall(began, null))
    }

    @Test
    fun `Q25 - the kept session is stopped and rescued once that call ends`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start)
        OrphanRecorderSession.resolve(recorder, env, "app start")
        env.inCall = false

        val outcome = OrphanRecorderSession.resolve(recorder, env, "phone call ended")

        assertEquals(Action.STOP_ORPHAN, outcome.action)
        assertEquals(listOf("call1"), recorder.finalised)
    }

    @Test
    fun `an unreadable call state keeps the session and says so`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = null, orphanStart = call1Start)
        assertEquals(Action.KEEP_LIVE_CALL, OrphanRecorderSession.resolve(recorder, env, "app start").action)
        assertTrue(env.lines.any { "could not read the call state" in it })
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
        val env = FakeEnv(inCall = false, orphanStart = call1Start)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "call start")

        assertEquals(StartVerdict.FAIL_BUSY, OrphanSessionPolicy.forStart(outcome.action, outcome.stillRecording))
        assertTrue(env.lines.any { "stop FAILED" in it })
    }

    @Test
    fun `an unanswerable recorder is left alone, and the always-on log says so`() {
        val recorder = object : OrphanRecorderSession.Recorder {
            override fun isRecording(): Boolean? = null
            override fun stopRecording() = error("must not be asked")
        }
        val env = FakeEnv()
        assertEquals(Action.NONE, OrphanRecorderSession.resolve(recorder, env, "app start").action)
        assertTrue(env.lines.any { "could not ask the recorder" in it })
    }

    // ---- a call-free moment (second review, MEDIUM-HIGH 3) ----

    @Test
    fun `a phone IDLE during a live WhatsApp call is not a call-free moment`() {
        // Q28: a ring-then-reject during WhatsApp; the audio mode is still IN_COMMUNICATION, so callInProgress reads true.
        val env = FakeEnv(inCall = true, liveCallBegan = call1Start)
        assertFalse(OrphanRecorderSession.noteNoCallIfFree(env))
        assertEquals(0L, env.lastNoCall)
        assertEquals(call1Start, env.liveCallBegan)
    }

    @Test
    fun `an app call ending under a live phone call is not a call-free moment`() {
        val env = FakeEnv(inCall = true)
        assertFalse(OrphanRecorderSession.noteNoCallIfFree(env))
        assertEquals(0L, env.lastNoCall)
    }

    @Test
    fun `no call at all is one, and an unreadable state is not`() {
        val free = FakeEnv(inCall = false, liveCallBegan = call1Start)
        assertTrue(OrphanRecorderSession.noteNoCallIfFree(free))
        assertTrue(free.lastNoCall > 0L)
        assertNull(free.liveCallBegan)
        assertFalse(OrphanRecorderSession.noteNoCallIfFree(FakeEnv(inCall = null)))
    }

    @Test
    fun `a call-free moment after the orphan started proves its call ended`() {
        assertTrue(OrphanSessionPolicy.orphanCallEnded(call1Start, call1Start + 1, null))
        assertFalse(OrphanSessionPolicy.orphanCallEnded(call1Start, call1Start - 1, null))
        assertFalse(OrphanSessionPolicy.orphanCallEnded(null, Long.MAX_VALUE, call1Start))
    }

    // ---- which file is the orphan's (second review, HIGH 2) ----

    @Test
    fun `the orphan's start is the file the app handed to the recorder, never just the newest note`() {
        assertEquals(call1Start, OrphanSessionPolicy.orphanStartFrom(call1Start, listOf(call1Start + 50_000L), 0L))
    }

    @Test
    fun `without that record, a leftover note from before the recorder was last idle is never taken`() {
        val idleAt = call1Start + 10_000L
        assertNull(OrphanSessionPolicy.orphanStartFrom(null, listOf(call1Start), idleAt))
        assertEquals(idleAt + 1, OrphanSessionPolicy.orphanStartFrom(null, listOf(call1Start, idleAt + 1), idleAt))
    }

    // ---- what a start does ----

    @Test
    fun `start verdicts`() {
        assertEquals(StartVerdict.START, OrphanSessionPolicy.forStart(Action.NONE, null))
        assertEquals(StartVerdict.START, OrphanSessionPolicy.forStart(Action.STOP_ORPHAN, false))
        assertEquals(StartVerdict.FAIL_BUSY, OrphanSessionPolicy.forStart(Action.STOP_ORPHAN, true))
        assertEquals(StartVerdict.ADOPT, OrphanSessionPolicy.forStart(Action.KEEP_LIVE_CALL, null))
        assertEquals(StartVerdict.FAIL_BUSY, OrphanSessionPolicy.forStart(Action.OWNED, null))
    }

    @Test
    fun `item 6 - the Wi-Fi switch's own still-running capture is kept, not an error`() {
        // An app-call mix start for a Wi-Fi call that did not confirm, nor confirm it stopped: the engine still owns
        // it, so the handoff and daemon paths keep it instead of failing the call.
        assertEquals(StartVerdict.KEEP_OWN, OrphanSessionPolicy.forStart(Action.OWNED, null, ownedBySelf = true))
    }

    @Test
    fun `C1 - a phone call's start waits for the app call being stopped for it`() {
        assertEquals(StartVerdict.WAIT_FOR_OWNER, OrphanSessionPolicy.forStart(Action.OWNED, null, otherOwnerReleasing = true))
    }

    @Test
    fun `C1 - the wait ends as soon as the app call's claim is given back`() {
        val voip = Any()
        val carrier = Any()
        DaemonSessionOwnership.claim(voip)
        assertFalse(DaemonSessionOwnership.othersAllReleasing(carrier))
        DaemonSessionOwnership.beginRelease(voip)
        assertTrue(DaemonSessionOwnership.othersAllReleasing(carrier))
        Thread { Thread.sleep(200); DaemonSessionOwnership.release(voip) }.start()
        val t0 = System.currentTimeMillis()
        assertTrue(DaemonSessionOwnership.awaitOthersReleased(carrier, 5_000L))
        assertTrue(System.currentTimeMillis() - t0 < 4_000L)
    }

    @Test
    fun `C1 - the wait is bounded`() {
        val voip = Any()
        DaemonSessionOwnership.claim(voip)
        DaemonSessionOwnership.beginRelease(voip)
        assertFalse(DaemonSessionOwnership.awaitOthersReleased(Any(), 100L))
    }

    @Test
    fun `decision matrix`() {
        assertEquals(Action.NONE, OrphanSessionPolicy.decide(false, false, true, false))
        assertEquals(Action.OWNED, OrphanSessionPolicy.decide(true, true, false, true))
        assertEquals(Action.STOP_ORPHAN, OrphanSessionPolicy.decide(true, false, false, false))
        assertEquals(Action.STOP_ORPHAN, OrphanSessionPolicy.decide(true, false, true, true))
        assertEquals(Action.KEEP_LIVE_CALL, OrphanSessionPolicy.decide(true, false, true, false))
    }

    // ---- gate review ----

    @Test
    fun `gate 1 - a ring during a recorded app call does not move the live call's beginning`() {
        // Q28: WhatsApp recorded, a phone call rings and is rejected. Noting it would make a later Q25 kill stop the
        // live app recording.
        assertFalse(OrphanSessionPolicy.mayNoteCarrierCallBegan(false, ringingOrFreshDial = true, appCallRecording = true, appCallModeUp = true))
        assertFalse(OrphanSessionPolicy.mayNoteCarrierCallBegan(false, ringingOrFreshDial = true, appCallRecording = false, appCallModeUp = true))
        assertFalse(OrphanSessionPolicy.mayNoteCarrierCallBegan(false, ringingOrFreshDial = true, appCallRecording = true, appCallModeUp = false))
        assertTrue(OrphanSessionPolicy.mayNoteCarrierCallBegan(false, ringingOrFreshDial = true, appCallRecording = false, appCallModeUp = false))
        assertFalse(OrphanSessionPolicy.mayNoteCarrierCallBegan(true, ringingOrFreshDial = true, appCallRecording = false, appCallModeUp = false))
        assertFalse(OrphanSessionPolicy.mayNoteCarrierCallBegan(false, ringingOrFreshDial = false, appCallRecording = false, appCallModeUp = false))
    }

    @Test
    fun `gate 1 - so a kill after a rejected ring keeps the live app recording`() {
        // The WhatsApp call began at call1Start - 5 s; its recording started at call1Start; the rejected ring 60 s in
        // is no longer noted, so the beginning stays before the recording and the restart adopts it.
        val recorder = FakeRecorder().apply { start("whatsapp") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, liveCallBegan = call1Start - 5_000L)
        assertEquals(Action.KEEP_LIVE_CALL, OrphanRecorderSession.resolve(recorder, env, "app start").action)
        assertEquals(0, recorder.stops)
    }

    @Test
    fun `gate 2 - a LINE call in MODE_NORMAL is a call in progress, by its players or recorders`() {
        val idleTelephony = 0
        assertTrue(OrphanSessionPolicy.isCallInProgress(idleTelephony, false, false, false, voiceCommPlayers = 1, voiceCommRecorders = 0))
        assertTrue(OrphanSessionPolicy.isCallInProgress(idleTelephony, false, false, false, voiceCommPlayers = 0, voiceCommRecorders = 1))
        assertTrue(OrphanSessionPolicy.isCallInProgress(idleTelephony, false, false, appCallRecording = true, 0, 0))
        assertFalse(OrphanSessionPolicy.isCallInProgress(idleTelephony, false, false, false, 0, 0))
    }

    @Test
    fun `gate 2 - what cannot be read never reads as no call`() {
        assertTrue(OrphanSessionPolicy.isCallInProgress(null, false, false, false, 0, 0))
        assertTrue(OrphanSessionPolicy.isCallInProgress(0, false, false, false, null, 0))
        assertTrue(OrphanSessionPolicy.isCallInProgress(2 /* OFFHOOK */, null, null, false, null, null))
    }

    @Test
    fun `gate 3 - an app call ending late does not wipe the phone call's session record`() {
        assertFalse(OrphanSessionPolicy.endsRecordedSession(recordedPath = "/s/carrier.tmp", endingPath = "/s/voip.tmp"))
        assertTrue(OrphanSessionPolicy.endsRecordedSession(recordedPath = "/s/voip.tmp", endingPath = "/s/voip.tmp"))
        assertFalse(OrphanSessionPolicy.endsRecordedSession(recordedPath = null, endingPath = "/s/voip.tmp"))
    }

    @Test
    fun `gate 3 - an app call whose stop did not take is stopped for the phone call, not adopted`() {
        // The app call's recording is still running and its record kept; the phone call answered during it was noted
        // as the live call's beginning (C1), after the app call's recording started.
        val recorder = FakeRecorder().apply { start("whatsapp") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, liveCallBegan = call1Start + 40_000L)

        val outcome = OrphanRecorderSession.resolve(recorder, env, "call start")

        assertEquals(StartVerdict.START, OrphanSessionPolicy.forStart(outcome.action, outcome.stillRecording))
        assertEquals(listOf("whatsapp"), recorder.finalised)
    }

    @Test
    fun `a stopped orphan whose file is known is marked and queued for the short-guard rescue`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val staged = File("/staging/call1.ogg")
        val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 1).apply { stagingFile = staged }

        OrphanRecorderSession.resolve(recorder, env, OrphanRecorderSession.TRIGGER_PHONE_CALL_ENDED)

        assertEquals(listOf(staged to true), env.marks)
        assertEquals(listOf(staged), env.rescuedNow)
        assertEquals("the quiet-wait request is still queued as the safety net", 1, env.rescues)
        assertTrue(env.lines.any { it.contains("instant rescue queued") && it.contains("savedInFull=true") })
    }

    @Test
    fun `a stop with no call up and the orphan's call over is saved in full, whatever the trigger`() {
        listOf("app start", "rescue check", "phone call ended").forEach { trigger ->
            val recorder = FakeRecorder().apply { start("call1") }
            val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 1).apply { stagingFile = File("/staging/call1.ogg") }

            OrphanRecorderSession.resolve(recorder, env, trigger)

            assertEquals(trigger, listOf(File("/staging/call1.ogg") to true), env.marks)
        }
    }

    @Test
    fun `a stop while another call is up is never saved in full (the next call's start)`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = true, orphanStart = call1Start, lastNoCall = call1Start + 1).apply { stagingFile = File("/staging/call1.ogg") }

        OrphanRecorderSession.resolve(recorder, env, "call start, /x")

        assertEquals(listOf(File("/staging/call1.ogg") to false), env.marks)
    }

    @Test
    fun `a later call in the call log downgrades saved-in-full, since the file may span two calls`() {
        listOf<Boolean?>(true, null).forEach { later ->
            val recorder = FakeRecorder().apply { start("call1") }
            val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 1)
                .apply { stagingFile = File("/staging/call1.ogg"); laterCall = later }

            OrphanRecorderSession.resolve(recorder, env, "app start")

            assertEquals("later=$later", listOf(File("/staging/call1.ogg") to false), env.marks)
            assertTrue(env.lines.any { it.contains("not marked saved-in-full") })
        }
    }

    @Test
    fun `an app-call orphan is never saved in full, the call log cannot see a second app call`() {
        val recorder = FakeRecorder().apply { start("wa1") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 1)
            .apply { stagingFile = File("/staging/wa1.ogg"); appCall = true }

        OrphanRecorderSession.resolve(recorder, env, "app call ended")

        assertEquals(listOf(File("/staging/wa1.ogg") to false), env.marks)
        assertTrue(env.lines.any { it.contains("app call") })
    }

    @Test
    fun `a quick redial is seen by the log guard, the slack is only clock slack`() {
        // A drops, the user redials within a minute: B is logged ~20 s after A's recording began; the guard must be asked
        // about a moment earlier than that.
        var asked = 0L
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 1).apply {
            stagingFile = File("/staging/call1.ogg")
            laterCallFn = { since -> asked = since; (call1Start + 20_000L) > since }
        }

        OrphanRecorderSession.resolve(recorder, env, "app start")

        assertTrue("asked since ${asked - call1Start} ms after the start", asked - call1Start <= 10_000L)
        assertEquals(listOf(false), env.marks.map { it.second })
    }

    @Test
    fun `a stop when the orphan's start is unknown is never saved in full`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = false, orphanStart = null).apply { stagingFile = File("/staging/call1.ogg") }

        OrphanRecorderSession.resolve(recorder, env, "app start")

        assertEquals(listOf(File("/staging/call1.ogg") to false), env.marks)
    }

    @Test
    fun `a failed stop is never marked or rescued at once`() {
        val recorder = FakeRecorder().apply { start("call1"); stopWorks = false }
        val env = FakeEnv(inCall = false, orphanStart = call1Start).apply { stagingFile = File("/staging/call1.ogg") }

        OrphanRecorderSession.resolve(recorder, env, OrphanRecorderSession.TRIGGER_PHONE_CALL_ENDED)

        assertTrue(env.marks.isEmpty())
        assertTrue(env.rescuedNow.isEmpty())
        assertEquals(1, env.rescues)
    }

    @Test
    fun `a stopped orphan with an unknown file only gets the quiet-wait rescue`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start)

        OrphanRecorderSession.resolve(recorder, env, "app start")

        assertTrue(env.rescuedNow.isEmpty())
        assertEquals(1, env.rescues)
    }

    @Test
    fun `a file that cannot be marked is not queued for the instant rescue, and the log says so`() {
        val recorder = FakeRecorder().apply { start("call1") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start).apply { stagingFile = File("/staging/call1.ogg"); markWorks = false }

        OrphanRecorderSession.resolve(recorder, env, OrphanRecorderSession.TRIGGER_PHONE_CALL_ENDED)

        assertTrue(env.rescuedNow.isEmpty())
        assertTrue(env.lines.any { it.contains("could not mark") })
    }

    @Test
    fun `the three quiet returns are logged with the trigger`() {
        val noRecorder = FakeEnv()
        OrphanRecorderSession.resolve(null, noRecorder, "call end check")
        assertTrue(noRecorder.lines.single().contains("[call end check]") && noRecorder.lines.single().contains("no recorder"))

        val idle = FakeEnv()
        OrphanRecorderSession.resolve(FakeRecorder(), idle, "rescue check")
        assertTrue(idle.lines.single().contains("[rescue check]") && idle.lines.single().contains("not recording"))

        val owned = FakeEnv(owned = true)
        OrphanRecorderSession.resolve(FakeRecorder().apply { start("x") }, owned, "phone call ended")
        assertTrue(owned.lines.single().contains("[phone call ended]") && owned.lines.single().contains("owned"))
    }

    @Test
    fun `an orphan stopped long after its call ended is marked with how long it ran on`() {
        val recorder = FakeRecorder().apply { start("c") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 600_000L).apply {
            stagingFile = File("c"); callEnded = call1Start + 300_000L; nowMs = call1Start + 300_000L + 125_000L
        }
        OrphanRecorderSession.resolve(recorder, env, "rescue check")
        assertEquals(listOf(125), env.ranOnMarks)
        assertTrue(env.lines.any { it.contains("file outlasts its call by 125 s") })
    }

    @Test
    fun `an orphan stopped soon after its call ended carries no run-on`() {
        val recorder = FakeRecorder().apply { start("c") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 600_000L).apply {
            stagingFile = File("c"); callEnded = call1Start + 300_000L; nowMs = call1Start + 300_000L + 20_000L
        }
        OrphanRecorderSession.resolve(recorder, env, "phone call ended")
        assertEquals(listOf(0), env.ranOnMarks)
    }

    @Test
    fun `the app-call flag travels with the stop mark`() {
        val recorder = FakeRecorder().apply { start("c") }
        val env = FakeEnv(inCall = false, orphanStart = call1Start, lastNoCall = call1Start + 600_000L).apply { stagingFile = File("c"); appCall = true }
        OrphanRecorderSession.resolve(recorder, env, "rescue check")
        assertEquals(listOf(true), env.appCallMarks)
    }

    @Test
    fun `run-on is told only past 30 s and only for the same call`() {
        assertEquals(null, OrphanSessionPolicy.ranOnSeconds(stoppedAt = 130_000, callEndedAt = 100_000, orphanStartedAt = 0))
        assertEquals(31, OrphanSessionPolicy.ranOnSeconds(stoppedAt = 131_000, callEndedAt = 100_000, orphanStartedAt = 0))
        assertEquals(null, OrphanSessionPolicy.ranOnSeconds(200_000, callEndedAt = null, orphanStartedAt = 0))
        assertEquals("an earlier call's end", null, OrphanSessionPolicy.ranOnSeconds(900_000, callEndedAt = 100_000, orphanStartedAt = 500_000))
        assertEquals(null, OrphanSessionPolicy.ranOnSeconds(stoppedAt = 50_000, callEndedAt = 100_000, orphanStartedAt = 0))
    }

    @Test
    fun `the call-end check lines say when it was scheduled and how late it ran`() {
        assertEquals("orphan check scheduled for +3 s", OrphanRecorderSession.scheduledLine(3_000))
        assertEquals("orphan check running (late by 61000 ms)", OrphanRecorderSession.runningLine(61_000))
    }

    @Test
    fun `a recording the daemon stopped itself after its call ended is marked saved in full with its run-on`() {
        val env = FakeEnv(inCall = false).apply {
            callEnded = call1Start + 300_000L; selfStopAt = call1Start + 300_000L + 41_000L
            finalised = OrphanRecorderSession.DaemonFinalised(File("c"), lastWriteAt = call1Start + 300_000L + 40_000L, startedAt = call1Start)
        }
        OrphanRecorderSession.resolve(FakeRecorder(), env, "rescue check")
        assertEquals(listOf(File("c") to true), env.marks)
        assertEquals(listOf(40), env.ranOnMarks)
        assertEquals(listOf(File("c")), env.rescuedNow)
    }

    @Test
    fun `a daemon-finalised file is not saved in full when the call log shows a later call or a call is up`() {
        val later = FakeEnv(inCall = false).apply {
            callEnded = call1Start + 1L; laterCall = true; selfStopAt = call1Start + 20L
            finalised = OrphanRecorderSession.DaemonFinalised(File("c"), call1Start + 10L, call1Start)
        }
        OrphanRecorderSession.resolve(FakeRecorder(), later, "rescue check")
        assertEquals(listOf(File("c") to false), later.marks)

        val inCall = FakeEnv(inCall = true).apply {
            callEnded = call1Start + 1L; selfStopAt = call1Start + 20L
            finalised = OrphanRecorderSession.DaemonFinalised(File("c"), call1Start + 10L, call1Start)
        }
        OrphanRecorderSession.resolve(FakeRecorder(), inCall, "rescue check")
        assertTrue(inCall.marks.isEmpty())
    }

    @Test
    fun `a file with no self-stop word from the daemon keeps the cut-off wording`() {
        val env = FakeEnv(inCall = false).apply {
            callEnded = call1Start + 1L; selfStopAt = null
            finalised = OrphanRecorderSession.DaemonFinalised(File("nosig"), call1Start + 10L, call1Start)
        }
        OrphanRecorderSession.resolve(FakeRecorder(), env, "rescue check")
        OrphanRecorderSession.resolve(FakeRecorder(), env, "rescue check")
        assertTrue(env.marks.isEmpty())
        assertEquals("the not-shown line comes once per file", 1, env.lines.count { it.contains("not a recording the daemon finished") })
    }

    @Test
    fun `not recording but owned here - the app's own file mid-teardown is never claimed`() {
        val env = FakeEnv(owned = true, inCall = false).apply {
            callEnded = call1Start + 1L; selfStopAt = call1Start + 20L
            finalised = OrphanRecorderSession.DaemonFinalised(File("own"), call1Start + 10L, call1Start)
        }
        OrphanRecorderSession.resolve(FakeRecorder(), env, "phone call ended")
        assertTrue(env.marks.isEmpty())
        assertTrue(env.rescuedNow.isEmpty())
        assertTrue(env.lines.any { it.contains("still owned here") })
    }

    @Test
    fun `finalised-after-call-end needs the daemon's word, a known call end before the last write, and no call now`() {
        assertTrue(OrphanSessionPolicy.finalisedAfterCallEnd(0, 100, 150, false, selfStopAt = 160))
        assertFalse("no self-stop word", OrphanSessionPolicy.finalisedAfterCallEnd(0, 100, 150, false, null))
        assertFalse("written long after the self-stop", OrphanSessionPolicy.finalisedAfterCallEnd(0, 100, 150 + 10_000, false, selfStopAt = 160))
        assertFalse("self-stop before the call ended", OrphanSessionPolicy.finalisedAfterCallEnd(0, 100, 150, false, selfStopAt = 90))
        assertFalse(OrphanSessionPolicy.finalisedAfterCallEnd(0, 100, 99, false, 160))
        assertFalse(OrphanSessionPolicy.finalisedAfterCallEnd(0, null, 150, false, 160))
        assertFalse(OrphanSessionPolicy.finalisedAfterCallEnd(200, 100, 150, false, 160))
        assertFalse(OrphanSessionPolicy.finalisedAfterCallEnd(0, 100, 150, null, 160))
    }
}
