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
import com.baba.callvault.data.AppPreferences

/**
 * When the folder check may run, so it adds no wake-ups of its own on a phone in a good state (review 2026-11-20: users
 * report battery drain).
 *
 * The checks ride work that already runs: app start, Home, the keep-alive's existing one-minute tick (at most every
 * [KEEP_ALIVE_MAX_AGE_MS]) and the end of a call kept for want of a folder. A periodic WorkManager job exists only
 * when nothing else would notice: [TROUBLE_MINUTES] while the folder is bad or calls are waiting, and
 * [GOOD_NO_KEEP_ALIVE_MINUTES] on a phone recording with no keep-alive (Shizuku mode). Never otherwise.
 */
object FolderWatchPlan {

    /** While the folder is bad or calls wait: often enough to save them soon after the folder is back. */
    const val TROUBLE_MINUTES = 60L

    /** Good folder, recording on, no keep-alive (Shizuku mode): app start and Home are the other triggers. */
    const val GOOD_NO_KEEP_ALIVE_MINUTES = 6 * 60L

    /** Home looks again only when the stored answer is older than this. */
    const val HOME_MAX_AGE_MS = 5 * 60_000L

    /** The keep-alive tick looks again only when the stored answer is older than this. */
    const val KEEP_ALIVE_MAX_AGE_MS = 5 * 60_000L

    /** A process start looks again only when the stored answer is older than this. */
    const val APP_START_MAX_AGE_MS = 30 * 60_000L

    /** The period of the periodic check, or null for none. */
    fun periodMinutes(keepAliveRuns: Boolean, recordingOn: Boolean, waitingCount: Int, folderBad: Boolean): Long? = when {
        folderBad || waitingCount > 0 -> TROUBLE_MINUTES
        recordingOn && !keepAliveRuns -> GOOD_NO_KEEP_ALIVE_MINUTES
        else -> null
    }

    /** Whether to measure again: never measured, older than [maxAgeMs], or not good (an unsettled answer). */
    fun isStale(state: FolderCheckState?, now: Long, maxAgeMs: Long): Boolean =
        state == null || state.kind != FolderReading.Kind.OK && state.kind != FolderReading.Kind.NOT_SET ||
            now - state.measuredAt !in 0 until maxAgeMs

    /** The inputs of [periodMinutes] on the phone. Cheap: preferences and one directory listing. */
    fun periodFor(context: Context): Long? {
        val prefs = AppPreferences(context)
        val state = RecordingFolderCheck.cached(context)
        return periodMinutes(
            // The keep-alive stands down in Shizuku mode.
            keepAliveRuns = !prefs.getPrivilegedMode().needsShizuku,
            recordingOn = prefs.getRecordingState().isRecordingAllowed,
            waitingCount = WaitingRecordings.count(context),
            folderBad = state != null && state.kind != FolderReading.Kind.OK && state.kind != FolderReading.Kind.NOT_SET,
        )
    }
}

/** Whether a phone or app call is up. Unknown reads as "up": waiting costs nothing, a provider query mid-call might. */
object PhoneCallState {
    fun isCallUp(context: Context): Boolean = runCatching {
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
        @Suppress("DEPRECATION")
        val carrier = context.getSystemService(TelephonyManager::class.java)?.callState
        inCall || (carrier != null && carrier != TelephonyManager.CALL_STATE_IDLE)
    }.getOrDefault(true)
}
