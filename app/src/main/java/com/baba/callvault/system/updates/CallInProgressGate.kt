/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy
import com.baba.callvault.services.recording.VoipRecordingCoordinator
import com.baba.callvault.utils.AppLogger

/**
 * Whether it is safe to install an update right now.
 *
 * Installing an APK over the running app kills the app process, and any recording in flight dies with
 * it. Seen on a device on 2026-07-30: one call became two files, and the second was mislabelled `out_`
 * with no contact name, because on restart the app read the still-running call as a fresh outgoing
 * one. Nothing was lost — the first file closed cleanly — but the call was split, and the seam was
 * silent for as long as the app took to come back.
 *
 * The updater is tap-to-install, so a user initiates it; nothing stops them tapping Install while
 * talking, and an update notification is exactly the kind of thing people poke at idly.
 *
 * **Known limit, stated rather than papered over:** a call that *starts* during an install is a few
 * seconds this cannot close. Closing it would mean aborting the installer mid-stream, and a
 * half-written APK is a broken app — worse than the problem.
 */
object CallInProgressGate {

    private const val TAG = "CV:UpdateGate"

    /** AudioManager.MODE_NORMAL, as a number to keep the pure decision free of Android. */
    private const val MODE_NORMAL = 0

    /**
     * Pure decision, so it can be tested without a device.
     *
     * Checking telephony alone is **not** enough: a VoIP call never sets the telephony call state, so
     * a telephony-only guard would install straight through a WhatsApp recording — the exact case this
     * exists to protect.
     *
     * Fails **open** on an unrecognised telephony value: refusing every update because a device
     * reports something unexpected would be worse than the rare split recording.
     */
    fun mayInstall(telephonyState: Int, voipRecording: Boolean, audioMode: Int = MODE_NORMAL): Boolean {
        if (voipRecording) return false
        // Review C3: an app call that is not being recorded (or one the OnePlus keeps out of the telephony state,
        // Q28) still holds a call audio mode, and installing then kills a recording that may start any second.
        if (AfterCallPolicy.isCallAudioMode(audioMode)) return false
        return when (telephonyState) {
            TelephonyManager.CALL_STATE_OFFHOOK,
            TelephonyManager.CALL_STATE_RINGING -> false
            else -> true
        }
    }

    /** Reads the live state. Any failure reads as "safe", for the same fail-open reason. */
    fun mayInstall(context: Context): Boolean {
        val telephony = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.callState
                ?: TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
        val voip = runCatching { VoipRecordingCoordinator.isRecording }.getOrDefault(false)
        val mode = runCatching { context.getSystemService(AudioManager::class.java)?.mode ?: MODE_NORMAL }
            .getOrDefault(MODE_NORMAL)
        val allowed = mayInstall(telephony, voip, mode)
        if (!allowed) {
            AppLogger.i(TAG, "Deferring the update install: a call is in progress (telephony=$telephony voip=$voip mode=$mode)")
        }
        return allowed
    }
}
