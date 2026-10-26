/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.transcription.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.R
import com.baba.callvault.transcription.HeavyWorkNotification
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

/**
 * Imports a model file the user already has, or backs an installed one up, through a storage URI the
 * user picked (GitHub #58). Thin on purpose: every decision — does the size fit, which model is this,
 * did the copy run long — lives in [ModelTransfer], which is unit-tested; this class only wires it to
 * the content resolver and WorkManager.
 *
 * **Never retried.** A failure here is about the picked file (wrong, unreadable, no room), and the
 * user is looking at the row waiting for the answer; retrying would not change the file. A partial
 * import is never resumed — the source may have changed in between, and the digest check would have
 * to start over anyway — so a stop only throws the partial copy away.
 *
 * **The URI grant is released after this job's own success or failure, and after a user's cancel —
 * never after any other stop.** A system stop is re-run from zero and needs it. A user cancel and a
 * REPLACE both look like [WorkInfo.STOP_REASON_CANCELLED_BY_APP], and a REPLACE with the same URI (the
 * user re-picking the file) needs it too — so the two are told apart by a mark: [cancelImport] and
 * [cancelBackup] note the model before cancelling, a REPLACE never does, and the stopped run releases
 * the grant only when its model is marked. The mark lives in this process, which is the one the
 * worker runs in; it cannot be done from the ViewModel, which does not know when the run has let go.
 */
class ModelTransferWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /**
     * The ongoing notification a running transfer shows. Used by [doWork], and asked for by WorkManager
     * if this ever runs as expedited work.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        HeavyWorkNotification.forWork(
            applicationContext,
            if (inputData.getString(KEY_OP) == OP_BACKUP) R.string.heavy_work_backing_up_model
            else R.string.heavy_work_importing_model
        )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_MODEL_ID)
        val model = ModelTransfer.catalogue().firstOrNull { it.id == id }
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
        if (model == null || uri == null) {
            AppLogger.e(TAG, "Transfer started without a known model or a uri (model=$id); nothing to do")
            return@withContext Result.failure()
        }
        // A background job is stopped after ten minutes, and copying 2.6 GB from a slow or cloud
        // provider can take longer — the stop deletes the partial copy and the re-run starts from zero,
        // so without this a big import could never finish. Best-effort, like the transcription and
        // summary workers: a phone that refuses the foreground service still gets the copy attempted.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { AppLogger.w(TAG, "Could not run the ${model.id} transfer in the foreground: ${it.message}") }
        when (inputData.getString(KEY_OP)) {
            OP_IMPORT -> importModel(model, uri)
            OP_BACKUP -> backupModel(model, uri)
            else -> {
                AppLogger.e(TAG, "Transfer of ${model.id} started with no known op")
                Result.failure()
            }
        }
    }

    // ---- import ----

    /**
     * Under the model's write lock for its whole length, including clean-up: a replaced import that
     * deleted the temp file after letting go of the lock could delete its successor's copy.
     */
    private suspend fun importModel(model: DownloadableModel, uri: Uri): Result {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            return ModelWriters.forModel(model).withLock {
                val dir = ModelRepository.modelsDir(applicationContext)
                val temp = ModelRepository.importFileFor(dir, model)
                try {
                    importUnderLock(dir, temp, model, uri, startedAt)
                } catch (e: CancellationException) {
                    val copiedSoFar = temp.length()
                    temp.delete()
                    log(OP_IMPORT, model, "stopped (${stopReasonName()})", copiedSoFar, startedAt)
                    throw e
                } catch (e: SecurityException) {
                    fail(OP_IMPORT, model, ERROR_UNREADABLE, startedAt, e)
                } catch (e: ModelTransfer.LongerThanExpectedException) {
                    // Read fine, but bigger than the model: the wrong file, not an unreadable one.
                    fail(OP_IMPORT, model, ERROR_WRONG_FILE, startedAt, e)
                } catch (e: IOException) {
                    // FileNotFoundException included: the file was moved or deleted since it was picked.
                    fail(OP_IMPORT, model, ERROR_UNREADABLE, startedAt, e)
                } catch (e: IllegalArgumentException) {
                    // Some providers throw this, or the one below, for a URI they no longer recognise.
                    fail(OP_IMPORT, model, ERROR_UNREADABLE, startedAt, e)
                } catch (e: UnsupportedOperationException) {
                    fail(OP_IMPORT, model, ERROR_UNREADABLE, startedAt, e)
                } finally {
                    temp.delete()
                }
            }
        } finally {
            // Not on a system stop or a REPLACE (see the class KDoc), including one while still waiting
            // for the lock; only on an end of its own or the user's cancel.
            if (shouldReleaseGrant(cancelledImports, model.id, isStopped)) releaseGrant(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun importUnderLock(dir: File, temp: File, model: DownloadableModel, uri: Uri, startedAt: Long): Result {
        if (ModelRepository.isInstalled(dir, model)) {
            log(OP_IMPORT, model, "already installed", 0L, startedAt)
            return Result.success()
        }
        when (val check = ModelTransfer.checkSize(pickedSize(uri), model, ModelTransfer.catalogue())) {
            ModelTransfer.ImportCheck.SizeMatches -> Unit
            ModelTransfer.ImportCheck.WrongFile -> return fail(OP_IMPORT, model, ERROR_WRONG_FILE, startedAt)
            is ModelTransfer.ImportCheck.OtherModel -> return otherModelOrWrong(model, check.model, uri, startedAt)
        }
        // A stale temp left by a killed process would otherwise count against the free space.
        temp.delete()
        val partial = ModelRepository.partFileFor(dir, model)
        val partialBytes = partial.length()
        val freeBytes = dir.usableSpace
        val room = ModelDownloadPolicy.importRoom(freeBytes, partialBytes, model.sizeBytes)
        AppLogger.i(TAG, "Import room for ${model.id}: free=${ModelDownloadPolicy.mb(freeBytes)} paused=${ModelDownloadPolicy.mb(partialBytes)} -> $room")
        when (room) {
            ModelDownloadPolicy.ImportRoom.FITS -> Unit
            ModelDownloadPolicy.ImportRoom.NO_ROOM -> return fail(OP_IMPORT, model, ERROR_NO_SPACE, startedAt)
            // GitHub #60: the paused download is the only thing in the way of the file the user chose.
            ModelDownloadPolicy.ImportRoom.FITS_IF_PARTIAL_DISCARDED -> {
                if (!partial.delete()) return fail(OP_IMPORT, model, ERROR_NO_SPACE, startedAt)
                log(OP_IMPORT, model, "discarded the paused download to make room", partialBytes, startedAt)
            }
        }

        val copied = openInput(uri).use { input ->
            temp.outputStream().use { output ->
                ModelTransfer.copyAndHash(input, output, model.sizeBytes, { publish(model, it) }, { isStopped })
            }
        }
        if (!ModelRepository.finalizeImport(dir, model)) {
            return fail(OP_IMPORT, model, ERROR_WRONG_FILE, startedAt)
        }
        log(OP_IMPORT, model, "installed", copied.bytes, startedAt)
        return Result.success()
    }

    /** The size names another catalogue model; only its digest may say so, or the file is just wrong. */
    private fun otherModelOrWrong(target: DownloadableModel, other: DownloadableModel, uri: Uri, startedAt: Long): Result {
        val digest = openInput(uri).use { ModelTransfer.hashOnly(it) { isStopped } }
        if (digest != other.sha256) return fail(OP_IMPORT, target, ERROR_WRONG_FILE, startedAt)
        log(OP_IMPORT, target, "failed $ERROR_OTHER_MODEL (is ${other.id})", 0L, startedAt)
        return Result.failure(
            workDataOf(
                KEY_MODEL_ID to target.id, KEY_ERROR to ERROR_OTHER_MODEL, KEY_OTHER_MODEL_ID to other.id,
                KEY_FINISHED_AT to System.currentTimeMillis()
            )
        )
    }

    /** The provider's size for [uri], or -1 when it reports none (the digest then decides). */
    private fun pickedSize(uri: Uri): Long =
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else UNKNOWN_SIZE
        } ?: UNKNOWN_SIZE

    private fun openInput(uri: Uri) =
        applicationContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("provider returned no stream")

    // ---- backup ----

    /** Copies the installed file out. The written length is checked; the digest is not re-read (spec). */
    private fun backupModel(model: DownloadableModel, uri: Uri): Result {
        val startedAt = SystemClock.elapsedRealtime()
        val source = ModelRepository.pathFor(applicationContext, model)
        var isWritten = false
        try {
            if (source == null) return fail(OP_BACKUP, model, ERROR_WRITE_FAILED, startedAt)
            val output = applicationContext.contentResolver.openOutputStream(uri, "w")
                ?: throw FileNotFoundException("provider returned no stream")
            val copied = output.use { out ->
                source.inputStream().use { input ->
                    ModelTransfer.copyAndHash(input, out, model.sizeBytes, { p -> publish(model, p) }, { isStopped })
                }
            }
            if (copied.bytes != model.sizeBytes) return fail(OP_BACKUP, model, ERROR_WRITE_FAILED, startedAt)
            isWritten = true
            log(OP_BACKUP, model, "written", copied.bytes, startedAt)
            return Result.success()
        } catch (e: CancellationException) {
            log(OP_BACKUP, model, "stopped (${stopReasonName()})", 0L, startedAt)
            throw e
        } catch (e: SecurityException) {
            return fail(OP_BACKUP, model, ERROR_WRITE_FAILED, startedAt, e)
        } catch (e: IOException) {
            return fail(OP_BACKUP, model, ERROR_WRITE_FAILED, startedAt, e)
        } catch (e: IllegalArgumentException) {
            return fail(OP_BACKUP, model, ERROR_WRITE_FAILED, startedAt, e)
        } catch (e: UnsupportedOperationException) {
            return fail(OP_BACKUP, model, ERROR_WRITE_FAILED, startedAt, e)
        } finally {
            // A system stop re-runs this from zero and "w" truncates, so it keeps the document. Any other
            // unfinished end (failure, cancel) deletes it; the grant is still held here, so the delete works.
            if (!isWritten && !willRunAgain()) {
                runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
            }
            // Not on a system stop or a REPLACE (see the class KDoc).
            if (shouldReleaseGrant(cancelledBackups, model.id, isStopped)) releaseGrant(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // ---- shared ----

    /** Only the whole percent: [ModelTransfer.copyAndHash] already calls this once per change. */
    private fun publish(model: DownloadableModel, percent: Int) {
        setProgressAsync(workDataOf(KEY_MODEL_ID to model.id, KEY_PERCENT to percent))
    }

    private fun releaseGrant(uri: Uri, flag: Int) {
        runCatching { applicationContext.contentResolver.releasePersistableUriPermission(uri, flag) }
    }

    /**
     * Stopped by Android rather than cancelled: WorkManager will start this request again from zero, so a
     * partial backup document is left for that run to overwrite. A cancel (by the user, or a replacing
     * request) is never re-run. Says nothing about the grant: see [shouldReleaseGrant].
     */
    private fun willRunAgain(): Boolean = isStopped && stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP

    private fun stopReasonName(): String =
        if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) "cancelled" else "reason $stopReason"

    private fun fail(op: String, model: DownloadableModel, error: String, startedAt: Long, cause: Throwable? = null): Result {
        log(op, model, "failed $error${cause?.let { " (${it.javaClass.simpleName})" } ?: ""}", 0L, startedAt)
        return Result.failure(
            workDataOf(KEY_MODEL_ID to model.id, KEY_ERROR to error, KEY_FINISHED_AT to System.currentTimeMillis())
        )
    }

    /** Model id, op, outcome, bytes and time only: never the user's URI or file name. */
    private fun log(op: String, model: DownloadableModel, outcome: String, bytes: Long, startedAt: Long) {
        AppLogger.i(TAG, "op=$op model=${model.id} $outcome bytes=$bytes ms=${SystemClock.elapsedRealtime() - startedAt}")
    }

    companion object {
        private const val TAG = "CV:ModelTransfer"

        const val KEY_MODEL_ID = "modelId"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"
        const val KEY_OTHER_MODEL_ID = "otherModelId"

        /** When a run failed, in epoch millis; shared with [ModelDownloadWorker] (see its KDoc). */
        const val KEY_FINISHED_AT = ModelDownloadWorker.KEY_FINISHED_AT

        private const val KEY_OP = "op"
        private const val KEY_URI = "uri"
        private const val OP_IMPORT = "import"
        private const val OP_BACKUP = "backup"
        private const val UNKNOWN_SIZE = -1L

        /** The picked file is not this model (size or digest). */
        const val ERROR_WRONG_FILE = "wrong-file"

        /** The picked file is a different catalogue model; [KEY_OTHER_MODEL_ID] names it. */
        const val ERROR_OTHER_MODEL = "other-model"

        /** The picked file could not be read: permission gone, moved, deleted or a read error. */
        const val ERROR_UNREADABLE = "unreadable"

        /** Not enough free space to hold the model. */
        const val ERROR_NO_SPACE = ModelDownloadWorker.ERROR_NO_SPACE

        /** The backup could not be written in full. */
        const val ERROR_WRITE_FAILED = "write-failed"

        /**
         * Models whose import or backup the user cancelled, so the stopped run knows to release its
         * grant (see the class KDoc). One set per op: cancelling a backup must not release an import's.
         */
        private val cancelledImports: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val cancelledBackups: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * Whether a run that has just ended releases its URI grant: true when it ended on its own
         * (`!isStopped`), or was stopped because the user cancelled it ([modelId] marked in
         * [cancelled]). Clears the mark on every end, so it cannot outlive the run it was meant for.
         * See the class KDoc for why a system stop and a REPLACE keep the grant.
         */
        internal fun shouldReleaseGrant(cancelled: MutableSet<String>, modelId: String, isStopped: Boolean): Boolean {
            val isMarked = cancelled.remove(modelId)
            return !isStopped || isMarked
        }

        fun importWorkName(model: DownloadableModel): String = "cv_model_import_${model.id}"

        fun backupWorkName(model: DownloadableModel): String = "cv_model_backup_${model.id}"

        /** Imports [model] from [source]; replaces any import of the same model already queued. */
        fun enqueueImport(context: Context, model: DownloadableModel, source: Uri) {
            // A fresh pick: a mark left by a cancel that reached no running job must not release this one's grant.
            cancelledImports.remove(model.id)
            val request = OneTimeWorkRequestBuilder<ModelTransferWorker>()
                .setInputData(inputFor(model, OP_IMPORT, source))
                // No storage-not-low constraint: the worker's own hasRoomFor check is the guard. The
                // constraint fires at a different, OEM-set threshold, so a phone between the two would
                // be stopped mid-copy, have the partial deleted, regain the space and re-run — forever.
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(importWorkName(model), ExistingWorkPolicy.REPLACE, request)
            AppLogger.i(TAG, "Queued import of ${model.id}")
        }

        /** Backs [model] up to [target]; replaces any backup of the same model already queued. */
        fun enqueueBackup(context: Context, model: DownloadableModel, target: Uri) {
            cancelledBackups.remove(model.id)
            val request = OneTimeWorkRequestBuilder<ModelTransferWorker>()
                .setInputData(inputFor(model, OP_BACKUP, target))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(backupWorkName(model), ExistingWorkPolicy.REPLACE, request)
            AppLogger.i(TAG, "Queued backup of ${model.id}")
        }

        /** The user's cancel: marks the model first, so the stopped run releases its read grant. */
        fun cancelImport(context: Context, model: DownloadableModel) {
            cancelledImports.add(model.id)
            WorkManager.getInstance(context).cancelUniqueWork(importWorkName(model))
        }

        /** The user's cancel: marks the model first, so the stopped run releases its write grant. */
        fun cancelBackup(context: Context, model: DownloadableModel) {
            cancelledBackups.add(model.id)
            WorkManager.getInstance(context).cancelUniqueWork(backupWorkName(model))
        }

        /** Progress percent reported by a running transfer, or null. */
        fun percentOf(progress: Data): Int? =
            progress.getInt(KEY_PERCENT, -1).takeIf { it >= 0 }

        private fun inputFor(model: DownloadableModel, op: String, uri: Uri): Data =
            workDataOf(KEY_MODEL_ID to model.id, KEY_OP to op, KEY_URI to uri.toString())
    }
}
