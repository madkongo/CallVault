/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * DIAGNOSTIC ONLY (#53): writes what the app can see of an app call's audio to the debug log, and
 * decides nothing.
 *
 * On one Honor phone WhatsApp keeps the audio mode after the far side hangs up, so our recording runs
 * on until the user leaves the chat. The mode is the only end signal we trust (muting must never end a
 * recording), so the question is whether another mute-proof signal ends at hang-up there. On the OP12
 * WhatsApp's speech playback went idle the moment the far side hung up. This logs it, on the
 * reporter's phone, next to the mode.
 *
 * Read from the normal audio callbacks the detector already has: no shell commands during a call.
 * The app sees playback anonymised (no package) and is only shown players that are ACTIVE, so a
 * count is the active count. Measured on the OP12 (2026-10-26): WhatsApp's call audio shows as usage
 * VOICE_COMMUNICATION (2) with content type UNKNOWN (0) — the usage is NOT hidden there, despite what
 * [VoipCallDetector]'s header says — and it vanished 27 ms before the mode left IN_COMMUNICATION.
 * Each player's usage:contentType is logged too, in case another ROM labels it differently.
 */
object VoipEndProbe {

    data class Snapshot(
        val mode: Int,
        /** Active voice-call players (usage VOICE_COMMUNICATION) — a call app's far side. */
        val voiceCallPlayers: Int,
        /** Every active player, as sorted "usage:contentType" pairs. */
        val playerKinds: List<String>,
        /** Recordings from the voice-call microphone source (WhatsApp's, and possibly ours). */
        val voiceCommRecorders: Int,
    )

    fun capture(audioManager: AudioManager): Snapshot {
        val players = runCatching { audioManager.activePlaybackConfigurations }.getOrDefault(emptyList())
        val recorders = runCatching { audioManager.activeRecordingConfigurations }.getOrDefault(emptyList())
        return Snapshot(
            mode = audioManager.mode,
            voiceCallPlayers = players.count { it.audioAttributes.usage == AudioAttributes.USAGE_VOICE_COMMUNICATION },
            playerKinds = players.map { "${it.audioAttributes.usage}:${it.audioAttributes.contentType}" }.sorted(),
            voiceCommRecorders = recorders.count {
                it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
            },
        )
    }

    fun describe(s: Snapshot): String {
        val kinds = if (s.playerKinds.isEmpty()) "" else s.playerKinds.joinToString(",", " [", "]")
        return "mode=${modeName(s.mode)} vcPlay=${s.voiceCallPlayers} " +
            "players=${s.playerKinds.size}$kinds vcRec=${s.voiceCommRecorders}"
    }

    fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        else -> "mode$mode"
    }

    /** Only changes are logged; the heartbeat covers "nothing changed for a while". */
    fun isWorthLogging(previous: Snapshot?, current: Snapshot): Boolean = previous != current
}
