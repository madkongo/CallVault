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
import java.util.Locale
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
        // (drive1). An unchanged schedule is KEEP: WorkManager leaves a live request alone and enqueues this one
        // when none is live (none, cancelled, failed). A changed schedule is a fresh request with a delay from now.
        //
        // Nothing here reads WorkManager's state first: apply() runs on the main thread (app start, Settings, the
        // end of a call), and that read waited up to 3 s behind WorkManager's own queue (review C25) without
        // changing what WorkManager does. Which of the two happened is read back off this thread, for the journal.
        val signature = signature(target, mode, hour, minute, prefs.getSyncDayOfWeek())
        val decision = decide(oldSignature, signature)
        val operation = workManager.enqueueUniquePeriodicWork(WORK_NAME, decision.policy, request)
        scheduler.edit().putString(KEY_SIGNATURE, signature).apply()
        AppLogger.i(TAG, "Periodic sweep ${decision.policy.name} (mode=$mode at $hour:$minute, initialDelayMs=$initialDelayMillis).")
        logOutcome(context, workManager, operation, request.id, decision, oldSignature, signature, initialDelayMillis / 60_000)
    }

    /** What [apply] does with the stored request: the WorkManager policy and why. */
    internal data class Decision(val policy: ExistingPeriodicWorkPolicy, val reason: Reason)

    internal enum class Reason { UNCHANGED, CHANGED }

    /**
     * Unchanged signature -> KEEP (WorkManager keeps a live request, and enqueues the new one when nothing live
     * holds the name). Changed signature -> CANCEL_AND_REENQUEUE. Never UPDATE (see [apply]).
     */
    internal fun decide(oldSignature: String?, newSignature: String): Decision =
        if (oldSignature?.let(::latinDigits) != latinDigits(newSignature)) {
            Decision(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, Reason.CHANGED)
        } else {
            Decision(ExistingPeriodicWorkPolicy.KEEP, Reason.UNCHANGED)
        }

    /**
     * The always-on journal wording for a [Decision], once WorkManager has stored it. [enqueuedNow] is whether the
     * unique work now holds the request [apply] just built (null: it could not be read back, see [readBackFailure]);
     * [nextMin] is WorkManager's own next run for the request it holds, when known.
     */
    internal fun describe(
        decision: Decision,
        old: String?,
        new: String,
        firstRunMin: Long,
        enqueuedNow: Boolean?,
        nextMin: Long?,
        readBackFailure: String? = null,
    ): String = when {
        decision.reason == Reason.CHANGED ->
            "signature changed (from '${old ?: "none"}' to '$new') → re-enqueued, first run in $firstRunMin min (old request cancelled, run count reset)"
        enqueuedNow == true ->
            "signature unchanged ('$new') but no live request (none, cancelled or failed) → re-enqueued, first run in $firstRunMin min"
        enqueuedNow == false ->
            "signature unchanged → KEEP, next run in ${nextMin ?: "unknown"} min ('$new')"
        else ->
            "signature unchanged ('$new') → KEEP asked (leaves a live request alone, enqueues if none); " +
                "WorkManager could not be read back: ${readBackFailure ?: "no reason"}"
    }

    private const val READ_BACK_TIMEOUT_SECONDS = 10L

    /**
     * The schedule as one comparable string; only what changes WHEN or WHETHER the sweep runs.
     *
     * Formatted with [Locale.ROOT]: the default locale's `format` writes Persian, Bengali or Arabic digits,
     * so a signature stored under one language never matched one built under another and every app start
     * cancelled and re-enqueued the sweep (review C22).
     */
    internal fun signature(target: StorageTarget, mode: SyncScheduleMode, hour: Int, minute: Int, dayOfWeek: Int): String =
        "$mode/$target/" + String.format(Locale.ROOT, "%02d:%02d", hour, minute) +
            (if (mode == SyncScheduleMode.WEEKLY) "/day$dayOfWeek" else "") + "/net-connected"

    /**
     * [s] with every decimal digit, of any script, turned into its Latin form. A signature stored before the
     * C22 fix may hold native digits; read through this it compares equal to the same schedule now.
     */
    internal fun latinDigits(s: String): String = buildString(s.length) {
        for (c in s) {
            val d = Character.digit(c, 10)
            append(if (d >= 0) '0' + d else c)
        }
    }

    /**
     * Writes, off the caller's thread, what the enqueue did (from whether the unique work now holds [requestId])
     * and what WorkManager itself reports: state, attempt count and the next run time. Every wait has a time
     * limit, and a failure to read is a journal line with its reason.
     */
    private fun logOutcome(
        context: Context,
        workManager: WorkManager,
        operation: androidx.work.Operation,
        requestId: java.util.UUID,
        decision: Decision,
        old: String?,
        new: String,
        firstRunMin: Long,
    ) {
        Thread({
            val lines = runCatching {
                operation.result.get(READ_BACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val infos = workManager.getWorkInfosForUniqueWork(WORK_NAME).get(READ_BACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val enqueuedNow = infos.any { it.id == requestId }
                val nextMin = infos.firstOrNull { it.nextScheduleTimeMillis != Long.MAX_VALUE }
                    ?.let { (it.nextScheduleTimeMillis - System.currentTimeMillis()) / 60_000 }
                val state = if (infos.isEmpty()) "scheduler: WorkManager reports no request after enqueue"
                else infos.joinToString("; ") {
                    val next = it.nextScheduleTimeMillis
                    "scheduler: WorkManager reports state=${it.state} runAttemptCount=${it.runAttemptCount} next run in " +
                        (if (next == Long.MAX_VALUE) "n/a" else "${(next - System.currentTimeMillis()) / 60_000} min")
                }
                listOf(describe(decision, old, new, firstRunMin, enqueuedNow, nextMin), state)
            }.getOrElse {
                val why = "${it.javaClass.simpleName}: ${it.message}"
                AppLogger.w(TAG, "Could not read the sweep request back from WorkManager: $why")
                listOf(describe(decision, old, new, firstRunMin, null, null, why))
            }
            lines.forEachIndexed { i, line ->
                DriveCopyJournal.record(
                    context,
                    if (i == 0) "scheduler: $line, retry backoff linear ${CloudCopyPolicy.SWEEP_BACKOFF_MINUTES} min" else line,
                )
            }
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
