/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.utils.AppLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [TranscriptionWorker] that transcribes everything not yet done.
 *
 * Idempotent: [apply] reconciles the current prefs with WorkManager using
 * [ExistingPeriodicWorkPolicy.UPDATE], so it is safe to call from Settings, after boot, or whenever
 * a preference changes. Modelled directly on
 * [com.baba.callvault.system.storage.SyncScheduler] so there is one scheduling idiom in this app
 * rather than two.
 *
 * - MANUAL    -> no periodic work; only [runNow] transcribes anything.
 * - AUTOMATIC -> 24h period, first run at the next HH:mm.
 */
object TranscriptionScheduler {

    /** Unique WorkManager name for the periodic transcription sweep. */
    const val WORK_NAME = "cv_transcription_sweep"

    /** Unique name for a user-requested run, so repeated taps do not stack up duplicate work. */
    const val MANUAL_WORK_NAME = "cv_transcription_now"

    private const val TAG = "CV:TranscriptionScheduler"
    private const val DAILY_PERIOD_HOURS = 24L

    /**
     * Reconciles the periodic sweep with the current prefs: cancels it for MANUAL, otherwise
     * (re)schedules a daily run anchored to the configured HH:mm.
     */
    fun apply(context: Context) {
        val prefs = AppPreferences(context)
        val mode = prefs.getTranscriptionMode()
        val workManager = WorkManager.getInstance(context)

        // Manual and per-call both mean "no sweep": per-call keeps up with new calls and deliberately
        // never touches the back catalogue.
        if (!mode.needsPeriodicSweep) {
            workManager.cancelUniqueWork(WORK_NAME)
            AppLogger.i(TAG, "Periodic transcription cancelled (mode=$mode).")
            return
        }

        val hour = prefs.getTranscriptionHour()
        val minute = prefs.getTranscriptionMinute()

        // Charging and battery-not-low by default: a backlog can be hours of sustained CPU, which is
        // not something to spend someone's battery on while they are out with the phone in a pocket.
        // A cloud service (issue #48) also needs a network: Wi-Fi unless mobile data was allowed.
        val run = runConstraints(prefs, requiresCharging = prefs.getTranscriptionRequiresCharging())
        val constraints = Constraints.Builder()
            .setRequiresCharging(run.requiresCharging)
            .setRequiredNetworkType(run.networkType)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<TranscriptionWorker>(
            DAILY_PERIOD_HOURS, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(nextDailyDelayMillis(hour, minute), TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        AppLogger.i(TAG, "Periodic transcription scheduled at $hour:$minute.")
    }

    /**
     * Transcribes now, on request.
     *
     * Runs with **no constraints** on the phone (a cloud run still needs its network): the user asked
     * for this one and is presumably waiting, so making
     * them find a charger first would be obtuse. [displayName] names a single recording; null drains
     * the queue.
     *
     * [language] is a per-recording pick encoded by [TranscriptionLanguageChoice.encode], null meaning
     * "use the setting". It only reaches a named recording: a queue drain covers calls in whatever
     * languages happen to be waiting, and one answer could not be right for all of them.
     */
    fun runNow(
        context: Context,
        displayName: String? = null,
        requiresCharging: Boolean = false,
        language: String? = null,
        /**
         * The user started this from the app, after the confirmation dialog. Such a run posts no
         * "phone may get warm" notification unless it is long — see [TranscriptionNoticePolicy].
         */
        userRequested: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .apply {
                if (displayName != null) {
                    setInputData(
                        workDataOf(
                            TranscriptionWorker.KEY_DISPLAY_NAME to displayName,
                            // Absent unless the user picked one, which is what lets the worker tell
                            // "chose auto-detect" apart from "did not choose".
                            TranscriptionWorker.KEY_LANGUAGE to language,
                            TranscriptionWorker.KEY_USER_REQUESTED to userRequested
                        )
                    )
                }
                // A cloud run waits for its network even when tapped: Wi-Fi only means Wi-Fi only.
                val run = runConstraints(AppPreferences(context), requiresCharging)
                setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(run.requiresCharging)
                        .setRequiredNetworkType(run.networkType)
                        .build()
                )
            }
            .build()

        // APPEND rather than KEEP: two different recordings tapped in a row must both be transcribed,
        // while the queue-draining variant still cannot run twice at once.
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        AppLogger.i(TAG, "Transcription requested (${displayName ?: "whole queue"}).")
    }

    /**
     * Stops whatever is transcribing right now, without giving up the nightly sweep.
     *
     * WorkManager has no "stop this execution, keep the schedule" call: cancelling the unique
     * periodic work removes the *schedule* as well as the run. So the schedule is rebuilt immediately
     * afterwards by [apply], which also keeps this honest in MANUAL mode — there, apply cancels, so
     * stopping does not conjure a sweep the user never asked for.
     */
    suspend fun stopNow(context: Context) {
        // First, because it is the only thing that actually stops the work: cancelling the worker
        // does not interrupt whisper, which sits in one blocking native call.
        TranscriptionEngine.requestAbort()

        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(MANUAL_WORK_NAME)
        workManager.cancelUniqueWork(WORK_NAME)
        apply(context)

        // The cancelled worker cannot be trusted to reset the row it was working on — whisper sits in
        // a blocking native call, so the cancellation may never reach the Kotlin that would do it.
        // Do it here instead, from a process that is definitely still alive.
        val released = TranscriptionQueue.releaseStaleWork(context)
        AppLogger.i(TAG, "Transcription stopped on request; schedule re-applied, $released row(s) released.")
    }

    /**
     * Transcribes [displayName] if the user asked for each call to be done as it ends.
     *
     * A no-op in every other mode, so the recording path can call it unconditionally and stays free of
     * transcription policy.
     *
     * Honours the charging preference as a **constraint rather than a veto**: a call can end anywhere,
     * and a 30-minute one costs about 30 minutes of all-core CPU, so it should wait for a charger — but
     * only waiting, never skipping. This mode schedules no sweep, so anything dropped here would never
     * be transcribed at all.
     */
    fun transcribeAfterCallIfEnabled(context: Context, displayName: String) {
        val prefs = AppPreferences(context)
        val mode = prefs.getTranscriptionMode()
        if (!mode.transcribesOnCallEnd) {
            // The silent branch of the pair. Queueing already says why it happened; not queueing said
            // nothing at all, so "my call was never transcribed" and "transcription is set to nightly"
            // produced identical logs.
            AppLogger.i(TAG, "Not queueing '$displayName': transcription mode is $mode, which does not run on call end")
            return
        }

        val requiresCharging = prefs.getTranscriptionRequiresCharging()
        AppLogger.i(
            TAG,
            "Queueing $displayName: the call just ended" +
                if (requiresCharging) " (waits for a charger)" else ""
        )
        runNow(context, displayName, requiresCharging)
    }

    /** The constraints for one run under the current where-to-transcribe settings. */
    private fun runConstraints(prefs: AppPreferences, requiresCharging: Boolean): TranscriptionRouting.RunConstraints =
        TranscriptionRouting.constraints(
            isCloud = prefs.getTranscriptionService().isCloud,
            allowMobileData = prefs.getTranscriptionCloudMobileData(),
            requiresCharging = requiresCharging,
        )

    /** Millis from now until the next occurrence of [hour]:[minute] (today if ahead, else tomorrow). */
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
