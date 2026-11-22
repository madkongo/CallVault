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
import android.provider.DocumentsContract
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
import com.baba.callvault.services.recording.WaitingCallSteps
import com.baba.callvault.services.recording.aftercall.AfterCallDecision
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs [WaitingPublisher] on the phone (review N5). Scheduled when a call is kept, a folder is chosen, or the folder is found usable. */
class WaitingRecordingsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val attempts = app.getSharedPreferences(PUBLISH_PREFS, Context.MODE_PRIVATE)
        runCatching {
            WaitingPublisher.runExclusive(
                env = { PhoneEnv(app) },
                notes = { WaitingRecordings.dirOrNull(app)?.listFiles().orEmpty().toList() },
                ignoreCallState = WaitingPolicy::ignoreCallState,
                attempt = { attempts.getInt(KEY_ATTEMPT, 0) },
                after = { result ->
                    // Under the run lock: read, decide and schedule as one step, so no older run's retry overtakes it.
                    val attempt = attempts.getInt(KEY_ATTEMPT, 0)
                    attempts.edit().putInt(KEY_ATTEMPT, WaitingPolicy.attemptAfterRun(attempt, result.retryLater)).apply()
                    if (result.saved > 0) FolderProtection.onWaitingSaved(app, result.saved)
                    // Backing off, and stopping after a while (S6): the next app start, Home visit, folder check or call
                    // starts again, with the count starting again.
                    if (result.retryLater) {
                        if (WaitingPolicy.shouldRetry(attempt)) scheduleRetry(app, WaitingPolicy.retryDelayMs(attempt))
                        else AppLogger.w(TAG, "Still cannot save the waiting calls after $attempt retries; stopping until the next trigger, count reset")
                    }
                },
            )
        }.onFailure { AppLogger.e(TAG, "Saving waiting calls failed: ${it.message}", it) }
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
         * Saves without ever deleting or trusting a document that is not provably ours (second review 2026-11-20, B1/B2):
         * - a document already under the name is our earlier save only with the same known, non-zero size (a save killed
         *   before the private copy was removed); anything else keeps its name and this call takes the next free one;
         * - the bytes go into a document under our own staging name, renamed once complete; only a leftover under that
         *   staging name (ours, from a killed attempt) is ever deleted.
         * The private copy is removed by the caller only after this returns a document.
         */
        override fun publish(file: File, name: String, mimeType: String): Pair<Uri, String>? {
            val target = folder ?: return null
            val dir = DocumentFile.fromTreeUri(context, target) ?: return null
            // A lookup that throws is unknown, not free (F1): chooseOrNull logs it and the call stays private for later.
            fun sizeOf(n: String): Long? = dir.findFile(n)?.let { SafHelper.documentSize(context, it.uri) }
            val (finalName, alreadySaved) = WaitingPublisher.chooseOrNull(name, file.length(), ::sizeOf) { AppLogger.w(TAG, it) }
                ?: run {
                    AppLogger.e(TAG, "No safe name for '$name' in the folder now; keeping the call for later")
                    return null
                }
            if (finalName != name) AppLogger.w(TAG, "'$name' is taken by another file in the folder; saving this call as '$finalName'")
            if (alreadySaved) {
                AppLogger.i(TAG, "'$finalName' is already in the folder with the same size (an earlier save); not saving it twice")
                return dir.findFile(finalName)?.uri?.let { it to finalName }
            }
            val partialName = CloudCopyPolicy.stagingNameFor(finalName)
            runCatching { dir.findFile(partialName)?.let { SafHelper.deleteDocument(it, "a partial earlier save '$partialName'") } }
            val partial = dir.createFile(mimeType, partialName) ?: return null
            if (!SafHelper.writeStagedFileToUri(context, file, partial.uri)) {
                SafHelper.deleteDocument(partial, "the partial save '$partialName'")
                return null
            }
            val renamed = runCatching { DocumentsContract.renameDocument(context.contentResolver, partial.uri, finalName) }
                .onFailure { AppLogger.w(TAG, "Could not rename '$partialName' to '$finalName': ${it.message}") }
                .getOrNull()
            if (renamed != null) return renamed to actualName(renamed, finalName)
            // A provider that cannot rename: write under the final name directly; the partial copy is ours to remove.
            SafHelper.deleteDocument(partial, "the partial save '$partialName'")
            return SafHelper.publishStagedRecording(context, target, finalName, mimeType, file)?.let { it to actualName(it, finalName) }
        }

        /** The name the provider gave (it may number a clash), for the library, the details file and the log. */
        private fun actualName(uri: Uri, asked: String): String {
            val reported = runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            return WaitingPublisher.nameFromProvider(reported, asked).also {
                if (it != asked) AppLogger.w(TAG, "The folder named '$asked' '$it'; using that")
            }
        }

        override suspend fun beforeCatalog(name: String, uri: Uri, mimeType: String, note: WaitingNote): Boolean {
            WaitingCallSteps.writeDetails(context, name, note)
            return AfterCallDecision.tryHold(context, name, uri, mimeType, isVoip = note.extras?.isVoip ?: false)
        }

        override suspend fun catalog(name: String, uri: Uri, bytes: Long, startedAt: Long): Throwable? =
            RecordingCatalog.recordLocalReporting(context, name, uri, bytes, startedAt)

        /** The same steps, in the same order, as after a call saved straight into the folder. */
        override fun afterPublish(name: String, uri: Uri, mimeType: String, held: Boolean) {
            try {
                if (!held) {
                    StorageRouter.routeSidecar(context, name, MetadataSidecar.sidecarNameFor(name), "application/json")
                    TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name)
                }
                WaveformDrawWorker.enqueue(context, name, uri)
                if (!held) StorageRouter.route(context, uri, name, mimeType)
            } finally {
                // Asked last, once the library entry exists, and in a finally, as on the normal path.
                if (held) AfterCallDecision.ask(context, name)
            }
        }

        override suspend fun storeCutOffLength(name: String, uri: Uri) = RescuedDuration.store(context, name, uri)

        override fun record(line: String) = CallEndLog.record(context, line)

        override fun cutOffSaved(name: String, startedAt: Long, cutAt: Long, savedInFull: Boolean, ranOnSeconds: Int, appCall: Boolean) =
            CutOffRescueWorker.recordSaved(context, name, startedAt, cutAt, savedInFull, ranOnSeconds, appCall)
    }

    companion object {
        private const val TAG = "CV:WaitingPublish"
        private const val PERIODIC_NAME = "recording_folder_watch"

        private const val PUBLISH_PREFS = "waiting_publish"
        private const val KEY_ATTEMPT = "attempt"

        private const val WATCH_PREFS = "recording_folder_watch"
        private const val KEY_WATCH_PERIOD = "period_minutes"

        /** Saves the waiting calls soon. Queued after a run in progress, never instead of it. */
        /** Saves the waiting calls now, replacing a pending backed-off retry (F4). */
        fun schedule(context: Context) = enqueue(context, delayMs = 0L, fresh = true)

        private fun scheduleRetry(context: Context, delayMs: Long) = enqueue(context, delayMs, fresh = false)

        private fun enqueue(context: Context, delayMs: Long, fresh: Boolean) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<WaitingRecordingsWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WaitingPolicy.workName(fresh), WaitingPolicy.workPolicy(fresh), request)
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
        if (!CallGuard.shouldSkip(applicationContext, "periodic")) RecordingFolderCheck.checkNow(applicationContext, "periodic")
        return Result.success()
    }
}
