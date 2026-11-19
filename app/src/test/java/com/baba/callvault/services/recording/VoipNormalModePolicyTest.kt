/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import com.baba.callvault.services.recording.VoipNormalModePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 108: LINE keeps the audio mode NORMAL, so its call is started from the audio itself. */
class VoipNormalModePolicyTest {

    private val normal = AudioManager.MODE_NORMAL
    private val line = "jp.naver.line.android"
    private val held = VoipNormalModePolicy.HOLD_MS

    private fun decide(
        mode: Int = normal,
        carrier: Boolean = false,
        active: Boolean = false,
        players: Set<String> = setOf(line),
        recorders: Set<String> = setOf(line),
        heldMs: Long = held,
    ) = VoipNormalModePolicy.decide(mode, carrier, active, players, recorders, heldMs)

    @Test
    fun `LINE with a call-voice player and a call-voice recording starts`() {
        assertEquals(Verdict.START, decide())
    }

    @Test
    fun `another app in normal mode does not start`() {
        assertEquals(
            Verdict.NOT_ON_ALLOW_LIST,
            decide(players = setOf("us.zoom.videomeetings"), recorders = setOf("us.zoom.videomeetings")),
        )
    }

    @Test
    fun `a player from LINE and a recording from another app does not start`() {
        assertEquals(Verdict.NOT_ON_ALLOW_LIST, decide(recorders = setOf("com.example.dictation")))
    }

    @Test
    fun `a call-voice player alone does not start`() {
        assertEquals(Verdict.NO_VOICE_RECORDING, decide(recorders = emptySet()))
    }

    @Test
    fun `a call-voice recording alone does not start`() {
        assertEquals(Verdict.NO_VOICE_PLAYER, decide(players = emptySet()))
    }

    @Test
    fun `a carrier call does not start`() {
        assertEquals(Verdict.CARRIER_CALL, decide(carrier = true))
    }

    @Test
    fun `the mode-driven start is left alone`() {
        assertEquals(Verdict.MODE_IS_NOT_NORMAL, decide(mode = AudioManager.MODE_IN_COMMUNICATION))
        assertEquals(Verdict.MODE_IS_NOT_NORMAL, decide(mode = AudioManager.MODE_IN_CALL))
    }

    @Test
    fun `a call already being recorded is not started twice`() {
        assertEquals(Verdict.CALL_ALREADY_ACTIVE, decide(active = true))
    }

    @Test
    fun `both must be live for the hold time`() {
        assertEquals(Verdict.NOT_YET, decide(heldMs = held - 1))
        assertEquals(Verdict.START, decide(heldMs = held))
    }

    @Test
    fun `the allow list is just LINE`() {
        assertEquals(setOf("jp.naver.line.android"), VoipNormalModePolicy.NORMAL_MODE_CALL_APPS)
    }

    @Test
    fun `the call ends only when both are gone and the mode is normal`() {
        assertTrue(VoipNormalModePolicy.shouldEnd(normal, vcPlayers = 0, vcRecorders = 0))
        assertFalse(VoipNormalModePolicy.shouldEnd(normal, vcPlayers = 1, vcRecorders = 0))
        assertFalse(VoipNormalModePolicy.shouldEnd(normal, vcPlayers = 0, vcRecorders = 1))
        assertFalse(VoipNormalModePolicy.shouldEnd(AudioManager.MODE_IN_COMMUNICATION, 0, 0))
    }

    // ---- the dump, as the recorder host prints it (lines taken from a POCO F8 Ultra report) ----

    private val dump = """
        AudioPlaybackConfiguration piid:38247 deviceIds:[] type:android.media.MediaPlayer u/pid:10470/6498 state:started attr:AudioAttributes: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH flags=0x800 tags= bundle=null sessionId:29993 mutedState:none
        AudioPlaybackConfiguration piid:1975 deviceIds:[3] type:android.media.MediaPlayer u/pid:10273/5369 state:stopped attr:AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_UNKNOWN flags=0x800 tags= bundle=null sessionId:2049 mutedState:none
        AudioPlaybackConfiguration piid:37959 deviceIds:[3] type:android.media.AudioTrack u/pid:10454/20694 state:paused attr:AudioAttributes: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH flags=0xA00 tags= bundle=null sessionId:29713 mutedState:none
        riid 7701; active? true
          session:30001 -- source client=VOICE_COMMUNICATION, dev=AudioFormat -- uid:10470 -- patch:5 -- pack:jp.naver.line.android -- format client=x -- silenced:false -- effects client=
        riid 7702; active? true
          session:30009 -- source client=MIC, dev=AudioFormat -- uid:2000 -- patch:6 -- pack:com.android.shell -- format client=x -- silenced:false -- effects client=
        riid 7703; active? false
          session:30011 -- source client=VOICE_COMMUNICATION, dev=AudioFormat -- uid:10999 -- patch:7 -- pack:com.example.idle -- format client=x -- silenced:false -- effects client=
    """.trimIndent()

    @Test
    fun `the dump gives the live players and recordings and skips the stopped and paused ones`() {
        val owners = VoipNormalModePolicy.parseOwners(dump)
        assertEquals(1, owners.players.size)
        assertEquals(10470, owners.players[0].uid)
        assertEquals("USAGE_VOICE_COMMUNICATION", owners.players[0].usage)
        assertEquals(2, owners.recorders.size)
        assertEquals("jp.naver.line.android", owners.recorders[0].packageName)
    }

    @Test
    fun `only call-voice audio counts, and the owners are resolved to packages`() {
        val owners = VoipNormalModePolicy.parseOwners(dump)
        val players = VoipNormalModePolicy.voicePlayerPackages(owners) { uid -> if (uid == 10470) listOf(line) else emptyList() }
        val recorders = VoipNormalModePolicy.voiceRecorderPackages(owners) { emptyList() }
        assertEquals(setOf(line), players)
        assertEquals(setOf(line), recorders) // the shell's MIC capture (ours) is not a call-voice recording
        assertEquals(Verdict.START, decide(players = players, recorders = recorders))
    }

    @Test
    fun `an unreadable recording section falls back to the player's app, a readable one never does`() {
        val playerOnly = VoipNormalModePolicy.parseOwners(
            "AudioPlaybackConfiguration piid:1 deviceIds:[] type:android.media.MediaPlayer u/pid:10470/6498 state:started attr:AudioAttributes: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH"
        )
        val players = setOf(line)
        assertEquals(setOf(line), VoipNormalModePolicy.recorderPackagesOrPlayers(playerOnly, { emptyList() }, true, players))
        assertTrue(VoipNormalModePolicy.recorderPackagesOrPlayers(playerOnly, { emptyList() }, false, players).isEmpty())
        // The dump listed a recording (someone else's): it is read as it is, no fallback.
        val other = VoipNormalModePolicy.parseOwners(dump.lines().filter { !it.contains("30001") }.joinToString("\n"))
        assertTrue(VoipNormalModePolicy.recorderPackagesOrPlayers(other, { emptyList() }, true, players).isEmpty())
    }

    @Test
    fun `a media player and a mic recording are not a call`() {
        val owners = VoipNormalModePolicy.parseOwners(
            """
            AudioPlaybackConfiguration piid:1 deviceIds:[3] type:android.media.AudioTrack u/pid:10486/19175 state:started attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC flags=0xA00
            session:5 -- source client=MIC, dev=x -- uid:10486 -- patch:1 -- pack:jp.naver.line.android -- format client=x
            """.trimIndent()
        )
        assertTrue(VoipNormalModePolicy.voicePlayerPackages(owners) { listOf(line) }.isEmpty())
        assertTrue(VoipNormalModePolicy.voiceRecorderPackages(owners) { listOf(line) }.isEmpty())
    }

    // ---- review C9: the one-shot judge must never drop a live LINE call silently ----

    private fun next(failure: VoipNormalModePolicy.JudgeFailure?, verdict: Verdict?, pair: Boolean = true, tries: Int = 1) =
        VoipNormalModePolicy.afterJudge(failure, verdict, appSeesPair = pair, triesUsed = tries)

    @Test
    fun `a judge that meets a lookup already running tries again when it finishes`() {
        assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_AFTER_LOOKUP, next(VoipNormalModePolicy.JudgeFailure.LOOKUP_BUSY, null))
    }

    @Test
    fun `no recorder host or no answer from it is tried again a little later`() {
        assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_LATER, next(VoipNormalModePolicy.JudgeFailure.HOST_NOT_CONNECTED, null))
        assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_LATER, next(VoipNormalModePolicy.JudgeFailure.NO_ANSWER, null))
    }

    @Test
    fun `a dump that misses audio the app still sees is racy and tried again`() {
        assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_LATER, next(null, Verdict.NO_VOICE_PLAYER, pair = true))
        assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_LATER, next(null, Verdict.NO_VOICE_RECORDING, pair = true))
        assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_LATER, next(null, Verdict.NOT_YET, pair = true))
        // The pair is gone: nothing to retry for.
        assertEquals(VoipNormalModePolicy.JudgeNext.FINAL, next(null, Verdict.NO_VOICE_PLAYER, pair = false))
    }

    @Test
    fun `a successful dump that rules the pair out is final, so a game's voice chat costs one dump`() {
        assertEquals(VoipNormalModePolicy.JudgeNext.FINAL, next(null, Verdict.NOT_ON_ALLOW_LIST))
        assertEquals(VoipNormalModePolicy.JudgeNext.FINAL, next(null, Verdict.CARRIER_CALL))
        assertEquals(VoipNormalModePolicy.JudgeNext.FINAL, next(null, Verdict.CALL_ALREADY_ACTIVE))
        assertEquals(VoipNormalModePolicy.JudgeNext.FINAL, next(null, Verdict.MODE_IS_NOT_NORMAL))
    }

    @Test
    fun `a start is a start`() {
        assertEquals(VoipNormalModePolicy.JudgeNext.STARTED, next(null, Verdict.START))
    }

    @Test
    fun `after the fast tries a live candidate keeps being checked slowly, never given up`() {
        // Review C9 rework: a recorder host that reconnects after ~10 s (the app restarted mid-call, Q25) must still
        // get the call recorded; a permanent give-up after 7.5 s lost it.
        val max = VoipNormalModePolicy.MAX_FAST_JUDGE_TRIES
        assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_LATER, next(VoipNormalModePolicy.JudgeFailure.HOST_NOT_CONNECTED, null, tries = max - 1))
        for (tries in listOf(max, max + 1, 50, 10_000)) {
            assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_SLOW, next(VoipNormalModePolicy.JudgeFailure.HOST_NOT_CONNECTED, null, tries = tries))
            assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_SLOW, next(VoipNormalModePolicy.JudgeFailure.NO_ANSWER, null, tries = tries))
            assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_SLOW, next(VoipNormalModePolicy.JudgeFailure.LOOKUP_BUSY, null, tries = tries))
            assertEquals(VoipNormalModePolicy.JudgeNext.RETRY_SLOW, next(null, Verdict.NO_VOICE_PLAYER, pair = true, tries = tries))
        }
        assertTrue(VoipNormalModePolicy.JudgeNext.entries.none { it.name == "GIVE_UP" })
        assertTrue(VoipNormalModePolicy.JUDGE_SLOW_RETRY_MS > VoipNormalModePolicy.JUDGE_RETRY_MS)
        // A start on any try still starts; a successful "no" is still final.
        assertEquals(VoipNormalModePolicy.JudgeNext.STARTED, next(null, Verdict.START, tries = 10_000))
        assertEquals(VoipNormalModePolicy.JudgeNext.FINAL, next(null, Verdict.NOT_ON_ALLOW_LIST, tries = 10_000))
    }

    @Test
    fun `slow checks are logged now and then, not every time`() {
        val max = VoipNormalModePolicy.MAX_FAST_JUDGE_TRIES
        assertTrue(VoipNormalModePolicy.logSlowCheck(max))
        assertTrue(!VoipNormalModePolicy.logSlowCheck(max + 1))
        assertTrue(VoipNormalModePolicy.logSlowCheck(max + VoipNormalModePolicy.SLOW_CHECKS_PER_LOG_LINE))
    }

    @Test
    fun `a thousand identical checks write a handful of always-on lines, not one each`() {
        // Merge-gate item 1: with the host down for a long LINE call (Q25) every 10 s check wrote a line into the
        // 128 KB always-on journal, pushing out the outcome lines that file exists to keep.
        val gate = VoipNormalModePolicy.JudgeLogGate()
        val written = (1..1_000).mapNotNull { gate.admit("owners unknown (the recorder host gave no answer)") }
        assertTrue("${written.size} lines", written.size <= 12)
        assertTrue(written.first().contains("owners unknown"))
        assertTrue(written.drop(1).all { it.startsWith("still checking") })
        // A changed answer is written at once.
        assertEquals("players=[VOICE] allowList=MATCH", gate.admit("players=[VOICE] allowList=MATCH")!!.substringBefore(" (after"))
        // And the slow-check summary line is just as bounded.
        val slow = (VoipNormalModePolicy.MAX_FAST_JUDGE_TRIES..VoipNormalModePolicy.MAX_FAST_JUDGE_TRIES + 1_000)
            .count { VoipNormalModePolicy.logSlowCheck(it) }
        assertTrue("$slow slow lines", slow <= 12)
    }

    // ---- review C14: a LINE call that visits call mode and comes back is not ended by the mode ----

    @Test
    fun `a normal-started call back in NORMAL with its pair still live goes back to the pair watch`() {
        // NORMAL -> IN_COMMUNICATION -> NORMAL with LINE's call-voice player and recording live: one file goes on.
        assertTrue(VoipNormalModePolicy.handBackToPairWatch(startedNormal = true, mode = AudioManager.MODE_NORMAL, vcPlayers = 1, vcRecorders = 1))
    }

    @Test
    fun `the mode ends it when the pair is gone, or when it never started from the pair`() {
        assertFalse(VoipNormalModePolicy.handBackToPairWatch(startedNormal = true, mode = AudioManager.MODE_NORMAL, vcPlayers = 0, vcRecorders = 0))
        assertFalse(VoipNormalModePolicy.handBackToPairWatch(startedNormal = true, mode = AudioManager.MODE_NORMAL, vcPlayers = 1, vcRecorders = 0))
        // A mode-started call (WhatsApp, Telegram): unchanged, the mode rules end it (Q32's debounce included).
        assertFalse(VoipNormalModePolicy.handBackToPairWatch(startedNormal = false, mode = AudioManager.MODE_NORMAL, vcPlayers = 1, vcRecorders = 1))
        // Not NORMAL (a carrier call took the mode): not handed back.
        assertFalse(VoipNormalModePolicy.handBackToPairWatch(startedNormal = true, mode = AudioManager.MODE_IN_CALL, vcPlayers = 1, vcRecorders = 1))
    }
}
