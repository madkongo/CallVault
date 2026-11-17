/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime

/**
 * Remembers, for the rest of the day, that the Drive copier process could not list the folder, so a retry of
 * the sweep goes straight to listing in the app's own process (see [SyncSweepWorker]) instead of losing the
 * copier's time limit again. A day, not for good: the next day the copier gets another chance.
 */
object DriveListMemory {
    private const val PREFS = "drive_list_memory"
    private const val KEY_DAY = "copier_list_failed_day"
    private const val KEY_AT = "copier_list_failed_at"
    private const val KEY_WHY = "copier_list_failed_why"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What was remembered today ("14:03: <reason>"), or null when the copier has not failed to list today. */
    fun failureToday(context: Context): String? {
        val p = prefs(context)
        if (!CloudCopyPolicy.copierListFailedToday(p.getString(KEY_DAY, null), LocalDate.now().toString())) return null
        return "${p.getString(KEY_AT, "?")}: ${p.getString(KEY_WHY, "no reason kept")}"
    }

    fun remember(context: Context, why: String) {
        prefs(context).edit()
            .putString(KEY_DAY, LocalDate.now().toString())
            .putString(KEY_AT, LocalTime.now().withNano(0).toString())
            .putString(KEY_WHY, why.take(300))
            .apply()
    }
}
