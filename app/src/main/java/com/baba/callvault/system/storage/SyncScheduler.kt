/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.utils.AppLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [SyncSweepWorker] that copies recordings to the Drive folder on a
 * cadence. Idempotent: [apply] reconciles the current prefs with WorkManager using [ExistingPeriodicWorkPolicy.UPDATE],
 * so it is safe to call repeatedly (e.g. from the wizard, settings, or after boot).
 *
 * - LOCAL target or IMMEDIATE schedule -> no periodic work (per-recording copy handled by [StorageRouter]).
 * - DAILY  -> 24h period, first run at the next HH:mm.
 * - WEEKLY -> 7-day period, first run at the next dayOfWeek@HH:mm.
 */
object SyncScheduler {

    /** Unique WorkManager name for the periodic cloud sweep. */
    const val WORK_NAME = "cv_sync_sweep"

    private const val TAG = "CV:SyncScheduler"
    private const val DAILY_PERIOD_HOURS = 24L
    private const val WEEKLY_PERIOD_DAYS = 7L
    private const val DAYS_PER_WEEK = 7

    /**
     * Reconciles the periodic sweep with the current prefs. Cancels it for LOCAL/IMMEDIATE; otherwise
     * (re)schedules a DAILY or WEEKLY [SyncSweepWorker] anchored to the configured HH:mm (and day).
     */
    fun apply(context: Context) {
        val prefs = AppPreferences(context)
        val target = prefs.getStorageTarget()
        val mode = prefs.getSyncScheduleMode()
        val workManager = WorkManager.getInstance(context)

        if (target == StorageTarget.LOCAL || mode == SyncScheduleMode.IMMEDIATE) {
            workManager.cancelUniqueWork(WORK_NAME)
            AppLogger.i(TAG, "Periodic sweep cancelled (target=$target mode=$mode).")
            return
        }

        val hour = prefs.getSyncTimeHour()
        val minute = prefs.getSyncTimeMinute()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val (repeatInterval, repeatUnit, initialDelayMillis) = when (mode) {
            SyncScheduleMode.DAILY -> Triple(
                DAILY_PERIOD_HOURS, TimeUnit.HOURS, nextDailyDelayMillis(hour, minute)
            )
            SyncScheduleMode.WEEKLY -> Triple(
                WEEKLY_PERIOD_DAYS, TimeUnit.DAYS, nextWeeklyDelayMillis(prefs.getSyncDayOfWeek(), hour, minute)
            )
            SyncScheduleMode.IMMEDIATE -> return // unreachable (handled above), keeps the when exhaustive
        }

        val request = PeriodicWorkRequestBuilder<SyncSweepWorker>(repeatInterval, repeatUnit)
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        AppLogger.i(TAG, "Periodic sweep scheduled (mode=$mode at $hour:$minute, initialDelayMs=$initialDelayMillis).")
    }

    /** Millis from now until the next occurrence of [hour]:[minute] (today if still ahead, else tomorrow). */
    private fun nextDailyDelayMillis(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val next = atTime(hour, minute)
        if (next.timeInMillis <= now) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis - now
    }

    /**
     * Millis from now until the next occurrence of [dayOfWeek] at [hour]:[minute].
     * [dayOfWeek] uses [java.util.Calendar] constants (SUNDAY=1..SATURDAY=7).
     */
    private fun nextWeeklyDelayMillis(dayOfWeek: Int, hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val next = atTime(hour, minute)
        // Days until the target weekday (0..6), normalised into the week.
        var dayDelta = (dayOfWeek - next.get(Calendar.DAY_OF_WEEK) + DAYS_PER_WEEK) % DAYS_PER_WEEK
        next.add(Calendar.DAY_OF_YEAR, dayDelta)
        if (next.timeInMillis <= now) next.add(Calendar.DAY_OF_YEAR, DAYS_PER_WEEK)
        return next.timeInMillis - now
    }

    /** A [Calendar] set to today at [hour]:[minute] with seconds/millis cleared. */
    private fun atTime(hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
