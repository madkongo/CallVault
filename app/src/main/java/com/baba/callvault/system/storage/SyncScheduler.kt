/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.work.BackoffPolicy
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
    private const val STATE_FILE = "cv_sync_scheduler"
    private const val KEY_SIGNATURE = "signature"
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

        val scheduler = context.applicationContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
        val oldSignature = scheduler.getString(KEY_SIGNATURE, null)

        if (target == StorageTarget.LOCAL || mode == SyncScheduleMode.IMMEDIATE) {
            workManager.cancelUniqueWork(WORK_NAME)
            scheduler.edit().remove(KEY_SIGNATURE).apply()
            AppLogger.i(TAG, "Periodic sweep cancelled (target=$target mode=$mode).")
            DriveCopyJournal.record(context, "scheduler: periodic sweep CANCELLED (target=$target mode=$mode; was '${oldSignature ?: "none"}')")
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

        // A short, linear retry delay instead of WorkManager's default (exponential up to 5 hours): see
        // [CloudCopyPolicy.SWEEP_BACKOFF_MINUTES].
        val request = PeriodicWorkRequestBuilder<SyncSweepWorker>(repeatInterval, repeatUnit)
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, CloudCopyPolicy.SWEEP_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        // UPDATE keeps the old request's run count, and so its retry delay: after a few failed runs the next
        // start is worked out from the backoff and the new time is ignored (drive1: a user moved the time to
        // 15:15 and nothing ran). So when the schedule itself changed — time, day, mode or target — the old
        // request is cancelled and a fresh one enqueued, which resets the count. A plain re-application (app
        // start, call end) leaves the signature unchanged and keeps UPDATE.
        val signature = signature(target, mode, hour, minute, prefs.getSyncDayOfWeek())
        val changed = signature != oldSignature
        val policy = if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE
        val operation = workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        scheduler.edit().putString(KEY_SIGNATURE, signature).apply()
        AppLogger.i(TAG, "Periodic sweep scheduled (mode=$mode at $hour:$minute, initialDelayMs=$initialDelayMillis).")
        DriveCopyJournal.record(
            context,
            "scheduler: policy=${policy.name}, ${if (changed) "schedule CHANGED from '${oldSignature ?: "none"}' to '$signature' (old request cancelled, run count reset)" else "unchanged '$signature'"}, " +
                "first run in ${initialDelayMillis / 60_000} min, retry backoff linear ${CloudCopyPolicy.SWEEP_BACKOFF_MINUTES} min",
        )
        logNextRun(context, workManager, operation)
    }

    /** The schedule as one comparable string; only what changes WHEN or WHETHER the sweep runs. */
    internal fun signature(target: StorageTarget, mode: SyncScheduleMode, hour: Int, minute: Int, dayOfWeek: Int): String =
        "$mode/$target/%02d:%02d".format(hour, minute) + if (mode == SyncScheduleMode.WEEKLY) "/day$dayOfWeek" else ""

    /**
     * Writes what WorkManager itself reports about the request once it is stored: state, attempt count and the
     * next run time. Off the caller's thread, because reading it waits on WorkManager's database; a failure to
     * read is a journal line as well.
     */
    private fun logNextRun(context: Context, workManager: WorkManager, operation: androidx.work.Operation) {
        Thread({
            val line = runCatching {
                operation.result.get()
                val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
                if (infos.isEmpty()) "scheduler: WorkManager reports no request after enqueue"
                else infos.joinToString("; ") {
                    val next = it.nextScheduleTimeMillis
                    "scheduler: WorkManager reports state=${it.state} runAttemptCount=${it.runAttemptCount} next run in " +
                        (if (next == Long.MAX_VALUE) "n/a" else "${(next - System.currentTimeMillis()) / 60_000} min")
                }
            }.getOrElse { "scheduler: could not read the request back from WorkManager (${it.javaClass.simpleName}: ${it.message})" }
            DriveCopyJournal.record(context, line)
        }, "cv-sync-sched-log").start()
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
