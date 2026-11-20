/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Whether a call is in progress or ringing, from every signal the app has, for callers that must not disturb the
 * recorder then (the stuck-microphone heal, review C54; the death budget, review R7).
 *
 * Deliberately wide: a false "yes" only makes those callers more conservative. The signals that miss a ringing app
 * call on their own (audio mode still NORMAL, no Telecom call, no recording yet) are covered by
 * [VoipRingMemory.anyRingActive] and the device call state, which self-managed calls do move to RINGING/OFFHOOK
 * (see [VoipTelephonyGate]). [TelecomManager.isInCall] is only ever a positive signal here, never a negative one
 * (see [CallEvidence]).
 */
object CallActivity {

    data class Inputs(
        val recordingNoticeHeld: Boolean,
        val voipRecording: Boolean,
        val audioModeInCall: Boolean,
        val managedCall: Boolean,
        val telecomInCall: Boolean,
        val deviceCallNotIdle: Boolean,
        val appCallRinging: Boolean,
    )

    fun isActive(inputs: Inputs): Boolean = with(inputs) {
        recordingNoticeHeld || voipRecording || audioModeInCall || managedCall ||
            telecomInCall || deviceCallNotIdle || appCallRinging
    }

    fun isActive(context: Context): Boolean = isActive(read(context))

    private fun read(context: Context) = Inputs(
        // The carrier recording service holds the shared notification for as long as it is in the foreground,
        // including a ringing call it is only offering to record.
        recordingNoticeHeld = SharedStatusNotice.isHeldByRecording,
        voipRecording = VoipRecordingCoordinator.isRecording,
        audioModeInCall = runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
            ?.let { it != AudioManager.MODE_NORMAL } == true,
        managedCall = VoipTelephonyGate.managedCallInProgress(context),
        telecomInCall = runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall }.getOrNull() == true,
        deviceCallNotIdle = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)?.callState
        }.getOrNull()?.let { it != TelephonyManager.CALL_STATE_IDLE } == true,
        appCallRinging = VoipRingMemory.anyRingActive(SystemClock.elapsedRealtime()),
    )
}
