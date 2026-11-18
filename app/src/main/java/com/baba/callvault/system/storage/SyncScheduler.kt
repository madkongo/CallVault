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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.utils.AppLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [SyncSweepWorker] that copies recordings to the Drive folder on a
 * cadence. Idempotent: [apply] reconciles the current prefs with WorkManager without disturbing a live request
 * whose schedule did not change (KEEP; never UPDATE), so it is safe to call repeatedly (e.g. from the wizard, settings, or after boot).
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

        // UPDATE is never used: it keeps the old request's enqueue time and run count, so a plain re-application
        // (app start, call end, settings screen) added a fresh delay to the OLD enqueue time and the sweep ran
        // minutes early or a day late (BabApps item 138), and after failed runs the backoff overrode a new time
        // (drive1). When the schedule is unchanged and the request is alive it is left alone (KEEP); when the
        // schedule changed, or no live request exists, a fresh one is enqueued with a delay from now.
        val signature = signature(target, mode, hour, minute, prefs.getSyncDayOfWeek())
        val existing = readExisting(workManager)
        val decision = decide(oldSignature, signature, existing?.map { it.state })
        val operation = workManager.enqueueUniquePeriodicWork(WORK_NAME, decision.policy, request)
        scheduler.edit().putString(KEY_SIGNATURE, signature).apply()
        AppLogger.i(TAG, "Periodic sweep ${decision.policy.name} (mode=$mode at $hour:$minute, initialDelayMs=$initialDelayMillis).")
        val nextMin = existing?.firstOrNull { it.nextScheduleTimeMillis != Long.MAX_VALUE }
            ?.let { (it.nextScheduleTimeMillis - System.currentTimeMillis()) / 60_000 }
        DriveCopyJournal.record(
            context,
            "scheduler: " + describe(decision, oldSignature, signature, initialDelayMillis / 60_000, nextMin) +
                ", retry backoff linear ${CloudCopyPolicy.SWEEP_BACKOFF_MINUTES} min",
        )
        logNextRun(context, workManager, operation)
    }

    /** What [apply] does with the stored request: the WorkManager policy and why (the journal wording comes from it). */
    internal data class Decision(val policy: ExistingPeriodicWorkPolicy, val reason: Reason)

    internal enum class Reason { UNCHANGED_ALIVE, CHANGED, NO_LIVE_WORK, WORK_STATE_UNREADABLE }

    private val ALIVE_STATES = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)

    /**
     * [states] are the states of the stored request (empty = none, null = could not be read). Unchanged signature
     * and a live request -> KEEP. Changed signature, or no live request (none, CANCELLED, FAILED...) ->
     * CANCEL_AND_REENQUEUE. If the state cannot be read, KEEP is still the safe choice: it leaves an existing
     * request alone and enqueues one when there is none.
     */
    internal fun decide(oldSignature: String?, newSignature: String, states: List<WorkInfo.State>?): Decision = when {
        oldSignature != newSignature -> Decision(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, Reason.CHANGED)
        states == null -> Decision(ExistingPeriodicWorkPolicy.KEEP, Reason.WORK_STATE_UNREADABLE)
        states.any { it in ALIVE_STATES } -> Decision(ExistingPeriodicWorkPolicy.KEEP, Reason.UNCHANGED_ALIVE)
        else -> Decision(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, Reason.NO_LIVE_WORK)
    }

    /** The always-on journal wording for a [Decision]; [nextMin] is WorkManager's own next run, when known. */
    internal fun describe(decision: Decision, old: String?, new: String, firstRunMin: Long, nextMin: Long?): String = when (decision.reason) {
        Reason.UNCHANGED_ALIVE -> "signature unchanged → KEEP, next run in ${nextMin ?: "unknown"} min ('$new')"
        Reason.CHANGED -> "signature changed (from '${old ?: "none"}' to '$new') → re-enqueued, first run in $firstRunMin min (old request cancelled, run count reset)"
        Reason.NO_LIVE_WORK -> "signature unchanged ('$new') but no live request (none, cancelled or failed) → re-enqueued, first run in $firstRunMin min"
        Reason.WORK_STATE_UNREADABLE -> "signature unchanged ('$new') but the stored request could not be read → KEEP (leaves an existing one alone, enqueues if none), planned first run in $firstRunMin min, next run in ${nextMin ?: "unknown"} min"
    }

    /** The stored request's infos, or null if WorkManager did not answer in time (journalled by [decide]'s reason). */
    private fun readExisting(workManager: WorkManager): List<WorkInfo>? =
        runCatching { workManager.getWorkInfosForUniqueWork(WORK_NAME).get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrNull()

    private const val READ_TIMEOUT_SECONDS = 3L

    /** The schedule as one comparable string; only what changes WHEN or WHETHER the sweep runs. */
    internal fun signature(target: StorageTarget, mode: SyncScheduleMode, hour: Int, minute: Int, dayOfWeek: Int): String =
        "$mode/$target/%02d:%02d".format(hour, minute) + (if (mode == SyncScheduleMode.WEEKLY) "/day$dayOfWeek" else "") + "/net-connected"

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
