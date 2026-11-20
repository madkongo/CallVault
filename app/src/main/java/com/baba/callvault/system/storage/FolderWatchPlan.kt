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

/**
 * When the folder check may run, so it adds no wake-ups of its own on a phone in a good state (review 2026-11-20: users
 * report battery drain).
 *
 * The checks ride work that already runs: process start, Home, the keep-alive's existing one-minute tick and the end of a
 * call kept for want of a folder. A good folder is looked at by the keep-alive at most every
 * [KEEP_ALIVE_GOOD_MAX_AGE_MS]; a bad one backs off ([badRetryMs]) rather than being probed on every tick. A periodic
 * WorkManager job exists only while the folder is bad or calls are waiting ([TROUBLE_MINUTES]); never on a phone in a
 * good state, in either mode (Shizuku mode has process start and Home).
 */
object FolderWatchPlan {

    /** While the folder is bad or calls wait: often enough to save them soon after the folder is back. */
    const val TROUBLE_MINUTES = 60L

    /** Home looks again at a good answer only when it is older than this (the app is open; no wake-up). */
    const val HOME_MAX_AGE_MS = 5 * 60_000L

    /** The keep-alive tick looks again at a good answer only when it is older than this. */
    const val KEEP_ALIVE_GOOD_MAX_AGE_MS = 60 * 60_000L

    /** A process start looks again at a good answer only when it is older than this. */
    const val APP_START_MAX_AGE_MS = 30 * 60_000L

    /** The period of the periodic check, or null for none. */
    fun periodMinutes(waitingCount: Int, folderBad: Boolean): Long? =
        if (folderBad || waitingCount > 0) TROUBLE_MINUTES else null

    /**
     * How long to wait before looking at a bad folder again, by how long it has been bad: 5, then 10, then 30, then
     * at most 60 minutes. An unconfirmed reading is settled by its own scheduled re-check, not by this.
     */
    fun badRetryMs(badForMs: Long): Long = when {
        badForMs < 30 * MINUTE -> 5 * MINUTE
        badForMs < 2 * 60 * MINUTE -> 10 * MINUTE
        badForMs < 6 * 60 * MINUTE -> 30 * MINUTE
        else -> 60 * MINUTE
    }

    private const val MINUTE = 60_000L

    /** Whether to measure again: never measured, or older than [goodMaxAgeMs] (good) or [badRetryMs] (not good). */
    fun isStale(state: FolderCheckState?, now: Long, goodMaxAgeMs: Long): Boolean {
        if (state == null) return true
        val age = now - state.measuredAt
        if (age < 0L) return true
        val good = state.kind == FolderReading.Kind.OK || state.kind == FolderReading.Kind.NOT_SET
        val badFor = if (state.firstBadAt > 0L) state.measuredAt - state.firstBadAt else 0L
        return age >= if (good) goodMaxAgeMs else badRetryMs(badFor)
    }

    /** The inputs of [periodMinutes] on the phone. Cheap: one preference read and one directory listing. */
    fun periodFor(context: Context): Long? {
        val state = RecordingFolderCheck.cached(context)
        return periodMinutes(
            waitingCount = WaitingRecordings.count(context),
            folderBad = state != null && state.kind != FolderReading.Kind.OK && state.kind != FolderReading.Kind.NOT_SET,
        )
    }
}

/**
 * Whether a phone or app call is up, so no provider query runs during one. The audio mode decides (it needs no
 * permission); the carrier call state is only the fallback, and something unreadable is not a call (second review
 * 2026-11-20, S6: "unknown is a call" skipped every check for ever on a phone without the phone-state permission).
 */
object PhoneCallState {
    fun isCallUp(context: Context): Boolean {
        val mode = runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
        @Suppress("DEPRECATION")
        val carrier = runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }.getOrNull()
        return decide(mode, carrier)
    }

    fun decide(audioMode: Int?, carrierState: Int?): Boolean {
        if (audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION ||
            audioMode == AudioManager.MODE_RINGTONE
        ) return true
        return carrierState != null && carrierState != TelephonyManager.CALL_STATE_IDLE
    }
}
