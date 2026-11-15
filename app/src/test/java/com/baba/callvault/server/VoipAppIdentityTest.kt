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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `dumpsys audio` fallback that names the app on the VoIP call.
 *
 * Lines below are real output captured during a live Telegram call (uid 10304) on Android 16. The
 * fallback only runs when the hidden playback-configuration API is unavailable, but it is what protects
 * the feature on OEM builds, so its selectivity is pinned here: an idle player, a media player, or a
 * player with a different usage must never be mistaken for the call.
 */
class VoipAppIdentityTest {

    private val telegramCall =
        "  AudioPlaybackConfiguration piid:9223 deviceIds:[3] type:android.media.AudioTrack " +
            "u/pid:10304/12153 state:started attr:AudioAttributes: usage=USAGE_VOICE_COMMUNICATION " +
            "content=CONTENT_TYPE_SPEECH flags=0x800 tags= bundle=null sessionId:11841"

    private val idleSound =
        "  AudioPlaybackConfiguration piid:87 deviceIds:[] type:android.media.SoundPool " +
            "u/pid:1000/3390 state:idle attr:AudioAttributes: usage=USAGE_ASSISTANCE_SONIFICATION " +
            "content=CONTENT_TYPE_SONIFICATION flags=0x800"

    private val musicPlaying =
        "  AudioPlaybackConfiguration piid:8015 deviceIds:[2] type:android.media.MediaPlayer " +
            "u/pid:10462/18221 state:started attr:AudioAttributes: usage=USAGE_MEDIA " +
            "content=CONTENT_TYPE_UNKNOWN flags=0x800"

    @Test
    fun `finds the uid of the app playing call audio`() {
        assertEquals(10304, VoipAppIdentity.parseVoiceCommUid(telegramCall))
    }

    @Test
    fun `picks the call out of a realistic player list`() {
        val dump = listOf(idleSound, musicPlaying, telegramCall).joinToString("\n")
        assertEquals(10304, VoipAppIdentity.parseVoiceCommUid(dump))
    }

    @Test
    fun `ignores music playing at the same time`() {
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseVoiceCommUid(musicPlaying))
    }

    @Test
    fun `ignores a call player that is not started`() {
        // A registered-but-idle voice-comm track is left over, not a live call.
        val idleCall = telegramCall.replace("state:started", "state:idle")
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseVoiceCommUid(idleCall))
    }

    @Test
    fun `ignores a started player with a different usage`() {
        val alarm = telegramCall.replace("USAGE_VOICE_COMMUNICATION", "USAGE_ALARM")
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseVoiceCommUid(alarm))
    }

    @Test
    fun `reads the uid of the app that owns communication mode`() {
        // The mode owner is set at the same instant as the mode change this feature reacts to, so it is
        // present even when the app's playback track has not started yet.
        val line = "  mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_COMMUNICATION, mPid=12153, mUid=10304"
        assertEquals(10304, VoipAppIdentity.parseModeOwnerUid(line))
    }

    @Test
    fun `ignores a carrier call, which this feature does not handle`() {
        val line = "  mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_CALL, mPid=1234, mUid=1001"
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseModeOwnerUid(line))
    }

    @Test
    fun `ignores the idle mode owner placeholder`() {
        // Real output when nothing is on a call; mUid=0 must not be taken for an app.
        val line = "  mAudioModeOwner: AudioModeInfo: mMode=MODE_NORMAL, mPid=0, mUid=0"
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseModeOwnerUid(line))
    }

    @Test
    fun `mode owner is found in a full dump alongside players`() {
        val dump = listOf(
            idleSound,
            "  mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_COMMUNICATION, mPid=12153, mUid=10304",
            musicPlaying,
        ).joinToString("\n")
        assertEquals(10304, VoipAppIdentity.parseModeOwnerUid(dump))
    }

    @Test
    fun `ignores the platform when it owns communication mode`() {
        // Real One UI output during a WhatsApp call: the SYSTEM owns the mode, not the app. Resolving
        // uid 1000 produced a recording named after Samsung's "Device maintenance", battery icon and all.
        val line = "  mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_COMMUNICATION, mPid=1371, mUid=1000"
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseModeOwnerUid(line))
    }

    @Test
    fun `falls through to the app's own playback track when the platform owns the mode`() {
        // The combination that must work on One UI: reject the system mode owner, take the real app.
        val dump = listOf(
            "  mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_COMMUNICATION, mPid=1371, mUid=1000",
            telegramCall,
        ).joinToString("\n")
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseModeOwnerUid(dump))
        assertEquals(10304, VoipAppIdentity.parseVoiceCommUid(dump))
    }

    @Test
    fun `ignores a platform-owned playback track`() {
        val systemTrack = telegramCall.replace("u/pid:10304/12153", "u/pid:1000/1371")
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseVoiceCommUid(systemTrack))
    }

    @Test
    fun `treats only installed-app uids as callers`() {
        assertTrue(VoipAppIdentity.isAppUid(10000))
        assertTrue(VoipAppIdentity.isAppUid(10304))
        assertFalse("uid 1000 is the platform", VoipAppIdentity.isAppUid(1000))
        assertFalse("shell is not a calling app", VoipAppIdentity.isAppUid(2000))
        assertFalse("root is not a calling app", VoipAppIdentity.isAppUid(0))
    }

    @Test
    fun `a skipped uid from the playback track lets the next track answer`() {
        // BabApps item 103: System UI (uid 10314) held a voice-communication track; the app rejected it.
        val systemUiTrack = telegramCall.replace("u/pid:10304/12153", "u/pid:10314/900")
        val dump = listOf(systemUiTrack, telegramCall).joinToString("\n")
        assertEquals(10314, VoipAppIdentity.parseVoiceCommUid(dump))
        assertEquals(10304, VoipAppIdentity.parseVoiceCommUid(dump, skip = setOf(10314)))
    }

    @Test
    fun `a skipped mode owner falls through to the playback track`() {
        val dump = listOf(
            "  mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_COMMUNICATION, mPid=900, mUid=10314",
            telegramCall,
        ).joinToString("\n")
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseModeOwnerUid(dump, skip = setOf(10314)))
        assertEquals(10304, VoipAppIdentity.parseVoiceCommUid(dump, skip = setOf(10314)))
    }

    @Test
    fun `returns unknown for empty or unrecognised output`() {
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseVoiceCommUid(""))
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseVoiceCommUid("dumpsys: not found"))
        assertEquals(VoipAppIdentity.UID_UNKNOWN, VoipAppIdentity.parseModeOwnerUid(""))
    }
}
