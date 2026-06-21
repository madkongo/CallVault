/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the daily [RetentionSweepWorker] that auto-deletes recordings older than the
 * configured retention period. Idempotent: [apply] reconciles the current prefs with WorkManager using
 * [ExistingPeriodicWorkPolicy.UPDATE], so it is safe to call repeatedly (settings change, app start).
 *
 * Retention OFF (both periods 0) -> no periodic work. Otherwise a single daily sweep handles BOTH the
 * device and Drive periods (the worker applies each per copy), anchored at [SWEEP_HOUR].
 */
object RetentionScheduler {

    /** Unique WorkManager name for the periodic retention sweep. */
    const val WORK_NAME = "cv_retention_sweep"

    private const val TAG = "CV:RetentionScheduler"
    private const val PERIOD_HOURS = 24L
    private const val SWEEP_HOUR = 3   // run at ~03:00 local, off-peak
    private const val SWEEP_MINUTE = 30

    /** Reconciles the periodic sweep with the current retention prefs. */
    fun apply(context: Context) {
        val prefs = AppPreferences(context)
        val maxDays = maxOf(prefs.getRetentionLocalDays(), prefs.getRetentionDriveDays())
        val workManager = WorkManager.getInstance(context)

        if (maxDays <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            AppLogger.i(TAG, "Retention sweep cancelled (retention off).")
            return
        }

        val request = PeriodicWorkRequestBuilder<RetentionSweepWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setInitialDelay(nextDailyDelayMillis(SWEEP_HOUR, SWEEP_MINUTE), TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        AppLogger.i(
            TAG,
            "Retention sweep scheduled (localDays=${prefs.getRetentionLocalDays()} driveDays=${prefs.getRetentionDriveDays()})."
        )
    }

    /** Millis from now until the next occurrence of [hour]:[minute] (today if still ahead, else tomorrow). */
    private fun nextDailyDelayMillis(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (next.timeInMillis <= now) next.add(Calendar.DAY_OF_YEAR, 1)
        return next.timeInMillis - now
    }
}
