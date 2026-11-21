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
import com.baba.callvault.integrations.adb.UsbDebuggingEvents
import com.baba.callvault.utils.AppLogger
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the daily [RetentionSweepWorker] that auto-deletes recordings older than the
 * configured retention period. Idempotent: [apply] leaves a live request alone when the schedule did not change
 * (KEEP), so it is safe to call repeatedly (settings change, app start).
 *
 * Retention OFF (both periods 0) -> no periodic work. Otherwise a single daily sweep handles BOTH the
 * device and Drive periods (the worker applies each per copy), anchored at [SWEEP_HOUR].
 */
object RetentionScheduler {

    /** Unique WorkManager name for the periodic retention sweep. */
    const val WORK_NAME = "cv_retention_sweep"

    private const val TAG = "CV:RetentionScheduler"
    private const val PERIOD_HOURS = 24L
    private const val STATE_FILE = "cv_retention_scheduler"
    private const val KEY_SIGNATURE = "signature"

    /**
     * Reconciles the periodic sweep with the current retention prefs.
     *
     * "Retention off" now means BOTH the age periods and the size cap are off. It used to mean only
     * the periods, which was correct while they were the sole reason to sweep and became a silent
     * bug the moment the storage cap was added: a user with a cap set and no age period would have
     * had the daily job cancelled out from under them, and the setting would simply never have done
     * anything, with the UI happily showing it enabled.
     */
    fun apply(context: Context, forceReanchor: Boolean = false) {
        val prefs = AppPreferences(context)
        val maxDays = maxOf(prefs.getRetentionLocalDays(), prefs.getRetentionDriveDays())
        val capBytes = prefs.getStorageCapBytes()
        val workManager = WorkManager.getInstance(context)
        val state = context.applicationContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)

        if (maxDays <= 0 && capBytes <= 0L) {
            workManager.cancelUniqueWork(WORK_NAME)
            state.edit().remove(KEY_SIGNATURE).apply()
            AppLogger.i(TAG, "Retention sweep cancelled (no age period and no size cap).")
            UsbDebuggingEvents.record(context, "retention: sweep cancelled (no age period and no size cap)")
            return
        }

        // Anchored to the device's LOCAL time zone (Calendar default TZ), so the chosen HH:mm fires at
        // local time wherever the user is.
        val hour = prefs.getRetentionTimeHour()
        val minute = prefs.getRetentionTimeMinute()
        val request = PeriodicWorkRequestBuilder<RetentionSweepWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setInitialDelay(nextDailyDelayMillis(hour, minute), TimeUnit.MILLISECONDS)
            .build()

        // Review C126. This used to be CANCEL_AND_REENQUEUE on every call, and apply() runs on every process start,
        // so when the sweep itself started the process, onCreate deleted that very job and moved it a day out before
        // it could run (and a time-zone broadcast did the same). Now it follows SyncScheduler: an unchanged
        // schedule is KEEP (WorkManager leaves a live request alone and enqueues when none holds the name); a changed
        // one, or a time/zone broadcast ([forceReanchor]), is CANCEL_AND_REENQUEUE so the new HH:mm takes effect at once.
        // Never UPDATE: it keeps the old schedule and ignores the new initial delay (measured 2026-08-04).
        val newSignature = signature(hour, minute, prefs.getRetentionLocalDays(), prefs.getRetentionDriveDays(), capBytes, TimeZone.getDefault().id)
        val oldSignature = state.getString(KEY_SIGNATURE, null)
        val decision = decide(oldSignature, newSignature, forceReanchor)
        workManager.enqueueUniquePeriodicWork(WORK_NAME, decision.policy, request)
        state.edit().putString(KEY_SIGNATURE, newSignature).apply()
        AppLogger.i(TAG, "Retention sweep ${decision.policy.name} at $hour:$minute local (localDays=${prefs.getRetentionLocalDays()} driveDays=${prefs.getRetentionDriveDays()}).")
        // Only a change is worth a line in the always-on log: KEEP happens on every app start.
        if (decision.policy != ExistingPeriodicWorkPolicy.KEEP) {
            UsbDebuggingEvents.record(
                context,
                "retention: sweep re-scheduled (${if (forceReanchor) "time or zone changed" else "settings changed"}) '$newSignature', first run in " +
                    "${nextDailyDelayMillis(hour, minute) / 60_000} min",
            )
        }
    }

    /** What [apply] does with the stored request: the WorkManager policy. */
    internal data class Decision(val policy: ExistingPeriodicWorkPolicy)

    internal fun decide(oldSignature: String?, newSignature: String, force: Boolean): Decision =
        if (force || oldSignature != newSignature) Decision(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
        else Decision(ExistingPeriodicWorkPolicy.KEEP)

    /** Everything that changes WHEN or WHETHER the sweep runs, as one comparable string (zone id so travel re-anchors). */
    internal fun signature(hour: Int, minute: Int, localDays: Int, driveDays: Int, capBytes: Long, zoneId: String): String =
        "$hour:$minute/local$localDays/drive$driveDays/cap$capBytes/$zoneId"

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
