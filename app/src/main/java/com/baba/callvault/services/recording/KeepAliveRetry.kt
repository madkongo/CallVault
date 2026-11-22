/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.RecordingState
import com.baba.callvault.integrations.adb.UsbDebuggingEvents
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Review R3: when Android refuses the keep-alive's foreground start, or the service gives up because its own
 * startForeground failed, nothing used to try again, so recording, the watchdog and app-call detection stayed off until
 * something else happened to start the app. This queues a few one-shot retries through WorkManager, which the system
 * holds even when the process is gone.
 *
 * Bounded ([MAX_ATTEMPTS], [RETRY_DELAY_MS] apart) so a phone that always refuses is retried a handful of times, not
 * for ever. It only ever adds a start attempt after a refusal: the normal start is untouched. Every step is written to
 * the always-on log.
 */
object KeepAliveRetry {

    private const val TAG = "CV:KeepAliveRetry"
    private const val WORK_NAME = "keep-alive-retry"
    internal const val KEY_ATTEMPT = "attempt"

    const val MAX_ATTEMPTS = 5
    const val RETRY_DELAY_MS = 3 * 60_000L

    fun delayForAttempt(attempt: Int): Long? = if (attempt in 1..MAX_ATTEMPTS) RETRY_DELAY_MS else null

    fun needsRetry(running: Boolean, userStopped: Boolean): Boolean = !running && !userStopped

    /**
     * The work policy of a queue request. A refusal seen from Application.onCreate must KEEP a chain that exists: each
     * retry's own process start runs onCreate, and REPLACE there reset the chain to attempt 1 for ever (a wake every
     * 3 minutes, review 2026-11-22). The chain's own worker and the other callers still REPLACE (attempt+1).
     */
    fun workPolicy(fromAppStart: Boolean): ExistingWorkPolicy = if (fromAppStart) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE

    /** Queues retry number [attempt] (1-based); replaces one already queued, except from app start ([fromAppStart]: keeps it). */
    fun schedule(context: Context, attempt: Int, why: String, fromAppStart: Boolean = false) {
        val delay = delayForAttempt(attempt)
        if (delay == null) {
            log(context, "keep-alive: not retrying the start any more ($why; $MAX_ATTEMPTS retries used)")
            return
        }
        runCatching {
            val request = OneTimeWorkRequestBuilder<KeepAliveRetryWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putInt(KEY_ATTEMPT, attempt).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, workPolicy(fromAppStart), request)
            log(context, "keep-alive: start retry $attempt of $MAX_ATTEMPTS queued in ${delay / 60_000} min ($why)")
        }.onFailure { log(context, "keep-alive: could not queue the start retry ($why): ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Drops a queued retry (the keep-alive was stopped on purpose). */
    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
            .onFailure { AppLogger.w(TAG, "Could not cancel the start retry: ${it.message}") }
    }

    private fun log(context: Context, line: String) {
        AppLogger.i(TAG, line)
        runCatching { UsbDebuggingEvents.record(context, line) }
    }

    /** Runs one retry: starts the keep-alive again unless it is already up or the user switched recording off. */
    class KeepAliveRetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val app = applicationContext
            val attempt = inputData.getInt(KEY_ATTEMPT, 1)
            val stopped = runCatching { AppPreferences(app).getRecordingState() == RecordingState.STOPPED }.getOrDefault(false)
            if (!needsRetry(running = DaemonKeepAliveService.isRunning, userStopped = stopped)) {
                log(app, "keep-alive: start retry $attempt not needed (running=${DaemonKeepAliveService.isRunning} userStopped=$stopped)")
                return Result.success()
            }
            log(app, "keep-alive: start retry $attempt of $MAX_ATTEMPTS")
            val accepted = DaemonKeepAliveService.startOnce(app, attempt)
            if (!accepted) schedule(app, attempt + 1, "start refused again")
            return Result.success()
        }
    }
}
