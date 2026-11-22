/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context

/** Keeps the live recording clock across an app-process kill (SharedPreferences, a few bytes, written on state changes only). */
class LiveClockPrefs(context: Context) : SharedStatusNotice.ClockStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("live_recording_clock", Context.MODE_PRIVATE)

    override fun load(): SharedStatusNotice.SavedClock? {
        if (!prefs.contains(KEY_SAVED_WALL)) return null
        return SharedStatusNotice.SavedClock(
            runningSinceMs = prefs.getLong(KEY_RUNNING, -1L),
            bankedMs = prefs.getLong(KEY_BANKED, 0L),
            bootWallMs = prefs.getLong(KEY_BOOT_WALL, 0L),
            savedWallMs = prefs.getLong(KEY_SAVED_WALL, 0L),
            startedWallMs = prefs.getLong(KEY_STARTED_WALL, 0L),
        )
    }

    override fun save(clock: SharedStatusNotice.SavedClock?) {
        prefs.edit().apply {
            if (clock == null) clear() else {
                putLong(KEY_RUNNING, clock.runningSinceMs)
                putLong(KEY_BANKED, clock.bankedMs)
                putLong(KEY_BOOT_WALL, clock.bootWallMs)
                putLong(KEY_SAVED_WALL, clock.savedWallMs)
                putLong(KEY_STARTED_WALL, clock.startedWallMs)
            }
        }.apply()
    }

    /** The orphan-session code's own record of the last moment no call was up. */
    override fun lastNoCallAt(): Long = runCatching {
        appContext.getSharedPreferences(OrphanRecorderSession.PREFS, Context.MODE_PRIVATE).getLong(OrphanRecorderSession.KEY_LAST_NO_CALL_AT, 0L)
    }.getOrDefault(0L)

    private companion object {
        const val KEY_RUNNING = "running_since"
        const val KEY_BANKED = "banked"
        const val KEY_BOOT_WALL = "boot_wall"
        const val KEY_SAVED_WALL = "saved_wall"
        const val KEY_STARTED_WALL = "started_wall"
    }
}
