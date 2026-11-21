/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.system.health

import android.content.Context
import androidx.core.content.edit
import com.baba.callvault.system.health.AppStopHistory.AppStart

/**
 * The wall-clock times the main process started, newest first, kept by the app itself (review C141).
 *
 * Below Android 15 the system lists no starts, so "was the app running again before the call?" could not be
 * answered and any user stop in the last day was blamed for a missed call. One prefs write per main-process start.
 * An empty ring (first run after the update) means "unknown": callers keep today's behaviour then.
 */
object AppStartRing {
    private const val FILE = "cv_app_start_ring"
    private const val KEY = "starts"
    const val CAPACITY = 16

    /** [ring] with [nowMillis] added in front, newest [CAPACITY] kept. Pure. */
    fun appended(ring: List<Long>, nowMillis: Long): List<Long> = (listOf(nowMillis) + ring).take(CAPACITY)

    fun parse(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.sortedDescending().orEmpty()

    @Synchronized
    fun record(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val next = appended(parse(prefs.getString(KEY, null)), nowMillis)
        prefs.edit { putString(KEY, next.joinToString(",")) }
    }

    fun starts(context: Context): List<AppStart> =
        parse(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))
            .map { AppStart(it, afterForceStop = false) }
}
