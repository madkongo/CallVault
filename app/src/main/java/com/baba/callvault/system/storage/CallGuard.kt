/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import com.baba.callvault.utils.AppLogger

/**
 * Whether a folder check should wait for a call to end (no provider query mid-call), with a cap (third review
 * 2026-11-20, F2): an app can leave the audio mode in a call long after it ended (WhatsApp holding IN_COMMUNICATION,
 * issue #53), and every check would then be skipped for ever. A real phone call always skips; an audio-mode-only
 * "call" skips for at most [STUCK_AFTER_MS], then checks go ahead. Each streak of skips is logged (first and count).
 */
object CallGuard {

    const val STUCK_AFTER_MS = 30 * 60_000L

    private const val TAG = "CV:CallGuard"
    private const val PREFS = "folder_call_guard"
    private const val KEY_STREAK = "streak_start"
    private const val KEY_SKIPS = "skips"

    data class Decision(val skip: Boolean, val streakStart: Long?, val overridden: Boolean = false)

    fun decide(audioCall: Boolean, carrierCall: Boolean, streakStart: Long?, now: Long): Decision = when {
        !audioCall && !carrierCall -> Decision(skip = false, streakStart = null)
        carrierCall -> Decision(skip = true, streakStart = streakStart ?: now)
        else -> {
            val start = streakStart ?: now
            if (now - start >= STUCK_AFTER_MS) Decision(skip = false, streakStart = start, overridden = true)
            else Decision(skip = true, streakStart = start)
        }
    }

    /** True: skip this check now. Persists the streak, so an app restart does not reset the cap. Never throws. */
    @Synchronized
    fun shouldSkip(context: Context, trigger: String): Boolean = runCatching {
        val mode = runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
        @Suppress("DEPRECATION")
        val carrier = runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }.getOrNull()
        val audioCall = PhoneCallState.decide(mode, null)
        val carrierCall = carrier != null && carrier != TelephonyManager.CALL_STATE_IDLE
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = prefs.getLong(KEY_STREAK, 0L).takeIf { it > 0L }
        val skips = prefs.getInt(KEY_SKIPS, 0)
        val d = decide(audioCall, carrierCall, start, System.currentTimeMillis())
        when {
            d.streakStart == null -> {
                if (start != null) AppLogger.i(TAG, "The call is over; $skips folder check(s) were skipped during it")
                prefs.edit().remove(KEY_STREAK).remove(KEY_SKIPS).apply()
            }
            d.skip -> {
                if (skips == 0) {
                    RecordingFolderCheck.recordAlwaysOn(context, "checks wait for the call to end ($trigger; audio mode $mode, phone call $carrierCall)")
                }
                prefs.edit().putLong(KEY_STREAK, d.streakStart).putInt(KEY_SKIPS, skips + 1).apply()
            }
            d.overridden -> {
                if (!prefs.getBoolean("overridden", false)) {
                    RecordingFolderCheck.recordAlwaysOn(
                        context,
                        "the audio mode has said 'in a call' for over ${STUCK_AFTER_MS / 60_000} min with no phone call " +
                            "($skips checks skipped); checking anyway",
                        important = true,
                    )
                }
                prefs.edit().putLong(KEY_STREAK, d.streakStart).putBoolean("overridden", true).apply()
            }
        }
        if (d.streakStart == null) prefs.edit().remove("overridden").apply()
        d.skip
    }.onFailure { AppLogger.w(TAG, "Call guard failed (checking anyway): ${it.message}") }.getOrDefault(false)
}
