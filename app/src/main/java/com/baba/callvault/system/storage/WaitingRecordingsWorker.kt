/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.waveform.WaveformDrawWorker
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs [WaitingPublisher] on the phone (review N5). Scheduled when a call is kept, a folder is chosen, or the folder is found usable. */
class WaitingRecordingsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val result = runCatching {
            WaitingPublisher.run(PhoneEnv(app), notes = WaitingRecordings.dirOrNull(app)?.listFiles().orEmpty().toList())
        }.onFailure { AppLogger.e(TAG, "Saving waiting calls failed: ${it.message}", it) }.getOrNull()
        if (result != null) {
            if (result.saved > 0) FolderProtection.onWaitingSaved(app, result.saved)
            if (result.retryLater) schedule(app, RETRY_DELAY_MS)
        }
        return Result.success()
    }

    private class PhoneEnv(private val context: Context) : WaitingPublisher.Env {
        private val folder: Uri? = AppPreferences(context).getRecordingFolderUri()
        override val files: List<File> = WaitingRecordings.files(context)
        override val now: Long = System.currentTimeMillis()

        override fun folderProblem(): String? = SafHelper.folderProblem(context, folder).also { problem ->
            if (problem != null) AppLogger.i(TAG, "${files.size} call(s) waiting; the recording folder is still not usable: $problem")
        }

        override fun callActive(): Boolean = PhoneCallState.isCallUp(context)

        /**
         * Idempotent and atomic (review 2026-11-20): written under a staging name and renamed, a leftover from a killed
         * attempt is cleaned up, and a same-name same-size file already there (a save killed before the private copy
         * was removed) is taken as saved instead of producing "x (1).ogg".
         */
        override fun publish(file: File, name: String, mimeType: String): Pair<Uri, String>? {
            val target = folder ?: return null
            val result = SafHelper.copyFileToFolder(context, Uri.fromFile(file), target, name, mimeType, file.length())
            if (result is SafHelper.CopyResult.Failed) AppLogger.w(TAG, "Saving '$name' failed: ${result.reason}")
            if (result is SafHelper.CopyResult.AlreadyPresent) AppLogger.i(TAG, "'$name' is already in the folder (an earlier save); not saving it twice")
            return WaitingPublisher.publishedFrom(result, name)
        }

        override suspend fun catalog(name: String, uri: Uri, bytes: Long, startedAt: Long): Throwable? =
            RecordingCatalog.recordLocalReporting(context, name, uri, bytes, startedAt)

        override fun afterPublish(name: String, uri: Uri, mimeType: String) {
            TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name)
            WaveformDrawWorker.enqueue(context, name, uri)
            StorageRouter.route(context, uri, name, mimeType)
        }

        override fun record(line: String) = CallEndLog.record(context, line)
    }

    companion object {
        private const val TAG = "CV:WaitingPublish"
        private const val WORK_NAME = "waiting_recordings_publish"
        private const val PERIODIC_NAME = "recording_folder_watch"

        /** Long enough for a cut-off waiting call to go quiet ([CutOffRescuePolicy.QUIET_MS]). */
        private const val RETRY_DELAY_MS = CutOffRescuePolicy.QUIET_MS + 30_000L

        private const val WATCH_PREFS = "recording_folder_watch"
        private const val KEY_WATCH_PERIOD = "period_minutes"

        /** Saves the waiting calls soon. Queued after a run in progress, never instead of it. */
        fun schedule(context: Context, delayMs: Long = 0L) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<WaitingRecordingsWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not schedule saving the waiting calls: ${it.message}") }
        }

        /**
         * Keeps the periodic folder check only while [FolderWatchPlan] says it is needed, at its period; cancels it
         * otherwise. Re-applied only when the period changes, so calling it often costs one preference read.
         */
        fun applyWatch(context: Context) {
            runCatching {
                val period = FolderWatchPlan.periodFor(context)
                val prefs = context.getSharedPreferences(WATCH_PREFS, Context.MODE_PRIVATE)
                val applied = prefs.getLong(KEY_WATCH_PERIOD, -1L)
                if (applied == (period ?: 0L)) return@runCatching
                val work = WorkManager.getInstance(context)
                if (period == null) {
                    work.cancelUniqueWork(PERIODIC_NAME)
                } else {
                    val request = PeriodicWorkRequestBuilder<FolderWatchWorker>(period, TimeUnit.MINUTES).build()
                    work.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
                }
                prefs.edit().putLong(KEY_WATCH_PERIOD, period ?: 0L).apply()
                AppLogger.i(TAG, "Periodic folder check: ${period?.let { "every $it min" } ?: "off"}")
            }.onFailure { AppLogger.w(TAG, "Could not set the periodic folder check: ${it.message}") }
        }
    }
}

/** The periodic folder check, only while [FolderWatchPlan] needs one (review C98, 2026-11-20). Skipped during a call. */
class FolderWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!PhoneCallState.isCallUp(applicationContext)) RecordingFolderCheck.checkNow(applicationContext, "periodic")
        return Result.success()
    }
}
