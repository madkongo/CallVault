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
        session:30001 -- source client=VOICE_COMMUNICATION, dev=AudioFormat -- uid:10470 -- patch:5 -- pack:jp.naver.line.android -- format client=x -- silenced:false -- effects client=
        session:30009 -- source client=MIC, dev=AudioFormat -- uid:2000 -- patch:6 -- pack:com.android.shell -- format client=x -- silenced:false -- effects client=
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
}
