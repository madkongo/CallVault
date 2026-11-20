/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.documentfile.provider.DocumentFile
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

        override fun callActive(): Boolean = runCatching {
            val mode = context.getSystemService(AudioManager::class.java)?.mode
            val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            val carrier = context.getSystemService(TelephonyManager::class.java)?.callState
            inCall || carrier == TelephonyManager.CALL_STATE_OFFHOOK
        }.getOrDefault(true) // unknown reads as "in a call", as in the cut-off rescue: waiting costs nothing

        override fun publish(file: File, name: String, mimeType: String): Pair<Uri, String>? {
            val target = folder ?: return null
            val uri = SafHelper.publishStagedRecording(context, target, name, mimeType, file) ?: return null
            // The provider renames on a clash ("x (1).ogg"); the library must hold the name it really has.
            val actual = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: name
            return uri to actual
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

        /** WorkManager's shortest period. */
        private const val WATCH_PERIOD_MINUTES = 15L

        /** Saves the waiting calls soon. Queued after a run in progress, never instead of it. */
        fun schedule(context: Context, delayMs: Long = 0L) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<WaitingRecordingsWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not schedule saving the waiting calls: ${it.message}") }
        }

        /** The periodic folder check, for when nothing else runs (no keep-alive in Shizuku mode, app not opened). */
        fun ensureWatch(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<FolderWatchWorker>(WATCH_PERIOD_MINUTES, TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { AppLogger.w(TAG, "Could not schedule the periodic folder check: ${it.message}") }
        }
    }
}

/** Looks at the recording folder every [15 minutes][WaitingRecordingsWorker.ensureWatch] (review C98). */
class FolderWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        RecordingFolderCheck.checkNow(applicationContext, "periodic")
        return Result.success()
    }
}
