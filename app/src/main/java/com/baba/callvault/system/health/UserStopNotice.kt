/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.RecordingState
import com.baba.callvault.system.health.AppStopHistory.ExitRecord
import com.baba.callvault.utils.AppLogger

/**
 * Tells the user, once per stop, that stopping CallVault (Active apps -> Stop, or Settings -> Force stop) switched
 * it off until it is opened again (BabApps item 115).
 *
 * A stopped app hears no calls. The user who pressed Stop is told nothing, finds the app running again at some
 * later call, and meets a missed call with no explanation. At the next start the system's own exit record says
 * what ended the previous process; if it was the user, a notification and a Home card say so, each once per stop.
 */
object UserStopNotice {

    private const val TAG = "CV:UserStopNotice"

    /** A stop older than this is history, not news: a fresh install of this feature must not announce last month. */
    const val MAX_AGE_MILLIS = 3L * 24 * 60 * 60 * 1000

    /** The stop to post the notification for, or null when there is none or it was already announced. */
    fun stopToNotify(exits: List<ExitRecord>, notifiedAtMillis: Long, nowMillis: Long): ExitRecord? =
        AppStopHistory.latestUserStop(exits)?.takeIf { it.atMillis > notifiedAtMillis && nowMillis - it.atMillis <= MAX_AGE_MILLIS }

    /** The stop the Home card is about, or null when none or its card was closed. */
    fun stopForCard(exits: List<ExitRecord>, dismissedAtMillis: Long, nowMillis: Long): ExitRecord? =
        AppStopHistory.latestUserStop(exits)?.takeIf { it.atMillis > dismissedAtMillis && nowMillis - it.atMillis <= MAX_AGE_MILLIS }

    /** At app start: post the notification once for the newest user stop. Never throws. */
    fun onAppStart(context: Context) {
        runCatching {
            val prefs = AppPreferences(context)
            val stop = stopToNotify(AppStopHistory.exits(context), prefs.getStopNoticeNotifiedAt(), System.currentTimeMillis())
                ?: return
            prefs.setStopNoticeNotifiedAt(stop.atMillis)
            // Switched off on purpose (the master switch): a stop then loses nothing, so say nothing.
            if (prefs.getRecordingState() == RecordingState.STOPPED) return
            AppLogger.i(TAG, "The app was stopped by the user at ${stop.atMillis} (\"${stop.description}\"); telling the user")
            SilentFailureNotifier.noteStoppedByUser(context)
        }.onFailure { AppLogger.w(TAG, "Stop notice failed: ${it.message}") }
    }

    /** The stop time the Home card shows now, or null for no card. */
    fun cardStopAt(context: Context): Long? = runCatching {
        val prefs = AppPreferences(context)
        if (prefs.getRecordingState() == RecordingState.STOPPED) return null
        stopForCard(AppStopHistory.exits(context), prefs.getStopNoticeCardDismissedAt(), System.currentTimeMillis())?.atMillis
    }.getOrNull()
}
