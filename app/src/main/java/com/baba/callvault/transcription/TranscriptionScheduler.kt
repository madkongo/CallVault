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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.transcription.cloud.TranscriptionService
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.utils.AppLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the periodic [TranscriptionWorker] that transcribes everything not yet done.
 *
 * Idempotent: [apply] reconciles the current prefs with WorkManager using
 * KEEP or CANCEL_AND_REENQUEUE by a stored signature (never UPDATE, review C127), so it is safe to call from Settings, after boot, or whenever
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

    /** Unique-name prefix of an after-call run; the recording's display name follows (review C124). */
    const val AFTER_CALL_WORK_PREFIX = "cv_transcription_after_call:"

    /** Unique name for the one-time retry a periodic sweep asks for instead of returning retry (review N36). */
    const val SWEEP_RETRY_WORK_NAME = "cv_transcription_sweep_retry"

    /** On every transcription request, so a stop (and the pill) can reach all of them whatever their unique name. */
    const val TAG_RUN = "cv_transcription_run"

    /** On the periodic sweep only: [TranscriptionWorker] reads it to know it must never return retry (review N36). */
    const val TAG_PERIODIC = "cv_transcription_periodic"

    /** Wait before the sweep's one-time retry; WorkManager's own backoff takes over if that run retries too. */
    private const val SWEEP_RETRY_DELAY_MINUTES = 15L

    /** Linear back-off of an after-call run that had to defer to another run: asked again every minute. */
    private const val AFTER_CALL_BACKOFF_MINUTES = 1L

    private const val TAG = "CV:TranscriptionScheduler"
    private const val DAILY_PERIOD_HOURS = 24L
    private const val STATE_FILE = "cv_transcription_scheduler"
    private const val KEY_SIGNATURE = "signature"

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
            // Forgotten, so switching back on re-enqueues instead of KEEP-ing a request that no longer exists.
            context.applicationContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
                .edit().remove(KEY_SIGNATURE).apply()
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
            .addTag(TAG_RUN)
            .addTag(TAG_PERIODIC)
            .build()

        // UPDATE is not used: it keeps the queued request's own timing, so a new hour was ignored once the
        // sweep had run (review C127). An unchanged schedule is KEEP (a live request is left alone, and one is
        // enqueued when none is live); a changed one is a fresh request with a delay counted from now.
        // Everything that changes WHEN or WHETHER the sweep runs is in the signature, because KEEP no longer
        // carries a changed constraint across the way UPDATE did.
        val state = context.applicationContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
        val signature = signature(hour, minute, run.requiresCharging, run.networkType)
        val policy = decide(state.getString(KEY_SIGNATURE, null), signature)
        workManager.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        state.edit().putString(KEY_SIGNATURE, signature).apply()
        AppLogger.i(TAG, "Periodic transcription ${policy.name} at $hour:$minute ($signature).")
    }

    /** Unchanged -> KEEP; changed, or never recorded -> CANCEL_AND_REENQUEUE. Never UPDATE (see [apply]). */
    internal fun decide(oldSignature: String?, newSignature: String): ExistingPeriodicWorkPolicy =
        if (oldSignature == newSignature) ExistingPeriodicWorkPolicy.KEEP
        else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE

    /** The schedule as one comparable string; digits are formatted with the root locale (see SyncScheduler C22). */
    internal fun signature(hour: Int, minute: Int, requiresCharging: Boolean, networkType: NetworkType): String =
        String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute) +
            "/charging=$requiresCharging/net=${networkType.name}"

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
        /** A per-run engine (#72 "transcribe again"); null uses the setting. Only reaches a named recording. */
        service: TranscriptionService? = null,
    ) {
        val request = oneTimeRequest(context, displayName, requiresCharging, language, userRequested, service)

        // APPEND rather than KEEP: two different recordings tapped in a row must both be transcribed,
        // while the queue-draining variant still cannot run twice at once.
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        AppLogger.i(TAG, "Transcription requested (${displayName ?: "whole queue"}).")
    }

    private fun oneTimeRequest(
        context: Context,
        displayName: String?,
        requiresCharging: Boolean,
        language: String?,
        userRequested: Boolean,
        service: TranscriptionService?,
        afterCall: Boolean = false,
    ): androidx.work.OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .apply {
                if (displayName != null) {
                    setInputData(
                        workDataOf(
                            TranscriptionWorker.KEY_DISPLAY_NAME to displayName,
                            // Absent unless the user picked one, which is what lets the worker tell
                            // "chose auto-detect" apart from "did not choose".
                            TranscriptionWorker.KEY_LANGUAGE to language,
                            TranscriptionWorker.KEY_USER_REQUESTED to userRequested,
                            TranscriptionWorker.KEY_SERVICE to service?.key,
                        )
                    )
                }
                // A cloud run waits for its network even when tapped: Wi-Fi only means Wi-Fi only.
                val run = runConstraints(AppPreferences(context), requiresCharging, service)
                setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(run.requiresCharging)
                        .setRequiredNetworkType(run.networkType)
                        .build()
                )
                addTag(TAG_RUN)
                if (afterCall) setBackoffCriteria(BackoffPolicy.LINEAR, AFTER_CALL_BACKOFF_MINUTES, TimeUnit.MINUTES)
            }
            .build()

    /**
     * The sweep's retry, as one-time work. A periodic worker that returns retry runs on WorkManager's
     * backoff (up to 5 h), not on its period, so a bad night could push the next run far off the chosen time
     * (review N36). The periodic work succeeds instead and asks for this: the same drain, a little later,
     * under the sweep's own constraints. KEEP, so two bad nights in a row do not stack up retries.
     */
    fun enqueueSweepRetry(context: Context) {
        val prefs = AppPreferences(context)
        val run = runConstraints(prefs, requiresCharging = prefs.getTranscriptionRequiresCharging())
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(run.requiresCharging)
                    .setRequiredNetworkType(run.networkType)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setInitialDelay(SWEEP_RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG_RUN)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SWEEP_RETRY_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        AppLogger.i(TAG, "Sweep retry queued in $SWEEP_RETRY_DELAY_MINUTES min.")
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
        workManager.cancelUniqueWork(SWEEP_RETRY_WORK_NAME)
        // Every other transcription request too (each after-call run is its own unique work, C124).
        workManager.cancelAllWorkByTag(TAG_RUN)
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
        // Its own unique work, not the chain a tap uses: an after-call run waiting for a charger must never
        // be the prerequisite of a tapped Transcribe, and Stop must not silently drop it (review C124).
        // KEEP: the same call queued twice is one run. The worker defers to any run already going
        // ([TranscriptionRunGate]); a short linear back-off keeps that wait short.
        val request = oneTimeRequest(context, displayName, requiresCharging, null, false, null, afterCall = true)
        WorkManager.getInstance(context).enqueueUniqueWork(
            AFTER_CALL_WORK_PREFIX + displayName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** The constraints for one run under the current where-to-transcribe settings. */
    private fun runConstraints(
        prefs: AppPreferences,
        requiresCharging: Boolean,
        service: TranscriptionService? = null,
    ): TranscriptionRouting.RunConstraints =
        TranscriptionRouting.constraints(
            isCloud = (service ?: prefs.getTranscriptionService()).isCloud,
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
