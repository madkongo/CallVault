/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.media.AudioManager
import com.baba.callvault.system.diagnostics.CallEndLog

/**
 * Evidence for R37, and nothing more: whether a transcription starts while a call is up.
 *
 * Whisper runs on up to six threads in the same process as the call services. Whether that costs a call any audio
 * is unproven, and yielding to a call would rest on [AudioManager.getMode] being back to normal after every call on
 * every phone, which has not been checked. So the behaviour is not changed; one line goes to the always-on log each
 * time a recording starts with the audio mode away from normal, so a report can show how often it happens.
 */
internal object TranscriptionCallOverlap {

    /** The always-on line for a transcription starting with the audio mode [audioMode], or null when it is normal or unknown. */
    fun lineFor(displayName: String, audioMode: Int?): String? {
        if (audioMode == null || audioMode == AudioManager.MODE_NORMAL) return null
        val mode = when (audioMode) {
            AudioManager.MODE_RINGTONE -> "ringtone"
            AudioManager.MODE_IN_CALL -> "in call"
            AudioManager.MODE_IN_COMMUNICATION -> "in communication (app call)"
            AudioManager.MODE_CALL_SCREENING -> "call screening"
            else -> "mode $audioMode"
        }
        return "transcription of ${CallEndLog.safeName(displayName)} started while the phone is $mode"
    }
}
