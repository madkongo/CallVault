/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a model, resumably, and installs it only once it hashes correctly.
 *
 * Resumable rather than foreground: these run from 190 MB to 3.46 GB and WorkManager stops a worker
 * after roughly ten minutes, so on any ordinary connection the download *will* be interrupted.
 * Rather than holding a foreground service open for however long that takes, an interrupted
 * download keeps its partial file and the next attempt continues from where it stopped with a Range
 * request. The user pays for each byte once.
 *
 * Constrained to unmetered networks by [enqueue]: silently pulling gigabytes over a phone's mobile
 * data would be indefensible.
 *
 * **The work request carries the model's details rather than its name.** Resolving an id would mean
 * this worker knowing every catalogue there is — including the summarisation one, which already
 * depends on this package for [DownloadableModel] — so the two would have to reference each other.
 * Passing the five fields it actually needs keeps the worker ignorant of what kind of model it is
 * fetching, which is the correct amount for it to know.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = requestedModel()
            ?: run {
                AppLogger.e(TAG, "Model download started with no model named; nothing to fetch")
                return@withContext Result.failure()
            }

        val dir = ModelRepository.modelsDir(applicationContext)
        if (ModelRepository.isInstalled(dir, model)) {
            AppLogger.i(TAG, "${model.id} is already installed")
            return@withContext Result.success()
        }

        // One writer per model file, across every attempt in this process. Android stops a long download
        // after ten minutes of background work and starts it again; if the stopped attempt is still inside
        // a blocking read when the new one begins, both used to append to the same .part — a duplicated
        // chunk, a file that fails its digest, "the download was damaged" (report 2026-09-28). The new
        // attempt now waits here until the old one has let go of the file.
        writerFor(model).withLock { downloadAndInstall(dir, model) }
    }

    /** Settles what an earlier attempt left, downloads the rest, verifies, installs. Runs under [writerFor]. */
    private suspend fun downloadAndInstall(dir: File, model: DownloadableModel): Result {
        var cleanRetriesUsed = 0
        while (true) {
            // Asked again under the lock: the attempt this one waited for may have finished the job.
            if (ModelRepository.isInstalled(dir, model)) return Result.success()
            val resumeFrom = when (val leftover = ModelRepository.settleLeftover(dir, model)) {
                ModelRepository.Leftover.Installed -> {
                    AppLogger.i(TAG, "${model.id}: an earlier attempt had finished it; verified and installed")
                    return Result.success()
                }
                is ModelRepository.Leftover.Resume -> leftover.bytes
                ModelRepository.Leftover.None, ModelRepository.Leftover.Discarded -> 0L
            }

            // Checked before a byte is fetched. At 3.46 GB a download that runs the phone out of space
            // does not merely fail — the system starts shedding processes on the way there. Failing up
            // front with a reason is the kinder outcome, and retrying cannot conjure storage.
            val remaining = model.sizeBytes - resumeFrom
            if (!ModelDownloadPolicy.hasRoomFor(dir.usableSpace, remaining)) {
                AppLogger.w(TAG, "Not enough free space for ${model.id}: needs ${ModelDownloadPolicy.mb(remaining)} plus headroom, has ${ModelDownloadPolicy.mb(dir.usableSpace)}")
                return Result.failure(workDataOf(KEY_ERROR to ERROR_NO_SPACE))
            }

            val part = ModelRepository.partFileFor(dir, model)
            val outcome = runCatching { download(model, part, resumeFrom) }.getOrElse { error ->
                AppLogger.w(TAG, "Download of ${model.id} failed: ${error.message}")
                return Result.retry()
            }
            when (outcome) {
                // Stopped mid-flight. The partial file stays, so the retry resumes.
                Outcome.STOPPED -> return Result.retry()
                // The server continued from the wrong place; the leftover is gone, start again from nothing.
                Outcome.RESTART -> continue
                Outcome.COMPLETE -> Unit
            }
            if (ModelRepository.finalizeDownload(dir, model)) return Result.success()
            // Digest mismatch: finalizeDownload has already deleted the file. One clean download from
            // nothing first — the damage is almost always made by an interrupted attempt — and only then
            // tell the user.
            if (ModelDownloadPolicy.retryCleanAfterDigestFailure(cleanRetriesUsed)) {
                cleanRetriesUsed++
                AppLogger.w(TAG, "${model.id} failed its digest; downloading it once more from the start")
                continue
            }
            return Result.failure(workDataOf(KEY_ERROR to ERROR_VERIFICATION_FAILED))
        }
    }

    /** How one pass over the network ended. */
    private enum class Outcome { COMPLETE, STOPPED, RESTART }

    /**
     * The model this request is for, assembled from the work's own input.
     *
     * Falls back to the whisper catalogue when the details are absent, which happens for exactly one
     * case: a download enqueued by an older version of the app that is still pending when the update
     * lands. Without this it would fail on resume and the user would pay for those bytes twice.
     */
    private fun requestedModel(): DownloadableModel? {
        val id = inputData.getString(KEY_MODEL_ID) ?: return null
        val url = inputData.getString(KEY_URL)
            ?: return TranscriptionModel.fromId(id)

        return RequestedModel(
            id = id,
            fileName = inputData.getString(KEY_FILE_NAME) ?: return null,
            url = url,
            sha256 = inputData.getString(KEY_SHA256) ?: return null,
            sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L).takeIf { it > 0L } ?: return null
        )
    }

    /** A model described entirely by the work request, so the worker needs no catalogue. */
    private data class RequestedModel(
        override val id: String,
        override val fileName: String,
        override val url: String,
        override val sha256: String,
        override val sizeBytes: Long
    ) : DownloadableModel

    /**
     * Streams [model] into [part] from [resumeFrom] (0 = a fresh file).
     *
     * The connection is dropped the moment this work is cancelled: a blocking read is not interrupted by
     * coroutine cancellation, and a stopped attempt left reading would keep the file — and [writerFor] —
     * for as long as the socket stays up.
     */
    private suspend fun download(model: DownloadableModel, part: File, resumeFrom: Long): Outcome = coroutineScope {
        val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (resumeFrom > 0L) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        val dropOnCancel = launch {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var written = resumeFrom
        var why = "error"
        try {
            val status = connection.responseCode
            AppLogger.i(TAG, ModelDownloadPolicy.attemptStartLine(
                model.id, runAttemptCount + 1, resumeFrom, model.sizeBytes, status, connection.getHeaderField("Content-Range"),
            ))
            val append = when (ModelDownloadPolicy.reply(status, resumeFrom, connection.getHeaderField("Content-Range"))) {
                ModelDownloadPolicy.Reply.APPEND -> true
                // A server that ignores the Range header replies 200 with the whole file, so anything
                // already written has to go or the two would be concatenated into garbage.
                ModelDownloadPolicy.Reply.START_OVER -> { part.delete(); false }
                ModelDownloadPolicy.Reply.DISCARD_AND_RETRY -> {
                    AppLogger.w(TAG, "${model.id}: server resumed from '${connection.getHeaderField("Content-Range")}', not byte $resumeFrom; starting over")
                    part.delete()
                    why = "the server resumed from the wrong place; leftover deleted"
                    return@coroutineScope Outcome.RESTART
                }
                ModelDownloadPolicy.Reply.ERROR -> { why = "HTTP $status"; throw IOException("HTTP $status") }
            }

            written = if (append) resumeFrom else 0L
            var lastPublished = NOTHING_PUBLISHED

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (isStopped) {
                            why = "stopped by Android (${stopReasonName()})"
                            return@coroutineScope Outcome.STOPPED
                        }

                        val read = input.read(buffer)
                        if (read <= 0) break

                        output.write(buffer, 0, read)
                        written += read

                        // Only when the whole percent moves. Publishing on every buffer is a
                        // WorkManager database write per 64 KB — about 8,700 of them for a 574 MB
                        // model and 52,800 for a 3.46 GB one, to move a bar with a hundred stops.
                        val percent = ModelDownloadPolicy.percentOf(written, model.sizeBytes)
                        if (ModelDownloadPolicy.shouldPublish(lastPublished, percent)) {
                            lastPublished = percent
                            setProgress(workDataOf(KEY_MODEL_ID to model.id, KEY_PERCENT to percent))
                        }
                    }
                }
            }

            if (written >= model.sizeBytes) { why = "complete"; Outcome.COMPLETE }
            else { why = "the server closed the connection early"; Outcome.STOPPED }
        } catch (e: IOException) {
            // A stopped attempt usually lands here: its connection is dropped under a blocking read.
            why = if (isStopped) "stopped by Android (${stopReasonName()})" else "error: ${e.message}"
            throw e
        } finally {
            dropOnCancel.cancel()
            connection.disconnect()
            AppLogger.i(TAG, ModelDownloadPolicy.attemptEndLine(
                model.id, why, (written - resumeFrom).coerceAtLeast(0L), written, model.sizeBytes,
                android.os.SystemClock.elapsedRealtime() - startedAt,
            ))
        }
    }

    /** Android's reason for stopping this attempt, readable in a log. The 10-minute limit reads "timeout". */
    private fun stopReasonName(): String = when (stopReason) {
        WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "network lost or no longer unmetered"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage low"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled in the app"
        WorkInfo.STOP_REASON_USER -> "stopped by the user"
        WorkInfo.STOP_REASON_QUOTA -> "background quota"
        WorkInfo.STOP_REASON_APP_STANDBY -> "app standby"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background restriction"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "device state"
        WorkInfo.STOP_REASON_PREEMPT -> "preempted"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system processing"
        else -> "reason $stopReason"
    }

    companion object {
        private const val TAG = "CV:ModelDownload"

        const val KEY_MODEL_ID = "modelId"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"

        /** The model's details, so the worker needs no catalogue to resolve them. */
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_URL = "url"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_SIZE_BYTES = "sizeBytes"

        /** The downloaded file did not match its published digest and was discarded. */
        const val ERROR_VERIFICATION_FAILED = "verification-failed"

        /** There was not enough free space, so nothing was fetched. */
        const val ERROR_NO_SPACE = "insufficient-storage"

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_BYTES = 1 shl 16

        /** One lock per model file, shared by every attempt in this process. See [doWork]. */
        private val writers = ConcurrentHashMap<String, Mutex>()

        private fun writerFor(model: DownloadableModel): Mutex = writers.getOrPut(model.fileName) { Mutex() }

        /** No figure has reached the progress store yet, so even 0% is news. */
        private const val NOTHING_PUBLISHED = -1

        /** Unique work name for [model], so tapping download twice does not fetch it twice. */
        fun workNameFor(model: DownloadableModel): String = "cv_model_download_${model.id}"

        /** Queues [model] for download over an unmetered network. Idempotent. */
        fun enqueue(context: Context, model: DownloadableModel) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODEL_ID to model.id,
                        KEY_FILE_NAME to model.fileName,
                        KEY_URL to model.url,
                        KEY_SHA256 to model.sha256,
                        KEY_SIZE_BYTES to model.sizeBytes
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(workNameFor(model), ExistingWorkPolicy.KEEP, request)
            AppLogger.i(TAG, "Queued download of ${model.id}")
        }

        /** Cancels an in-progress download. The partial file is kept so a later retry resumes. */
        fun cancel(context: Context, model: DownloadableModel) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(model))
        }

        /** Progress percent reported by a running download, or null. */
        fun percentOf(progress: Data): Int? =
            progress.getInt(KEY_PERCENT, -1).takeIf { it >= 0 }
    }
}
