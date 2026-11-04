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
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.utils.AppLogger

/**
 * Copies a finished recording from the local SAF folder to the Drive SAF folder and, when the storage
 * target is cloud-only, deletes the local source afterwards.
 *
 * The copy is idempotent and atomic (see [SafHelper.copyFileToFolder]), which matters because this
 * worker is retried: before that was true, every retry uploaded the recording *again*, so Google Drive
 * announced "saved a call" long after the call ended and the folder collected truncated twins.
 *
 * Retries are bounded by [CloudCopyPolicy.MAX_ATTEMPTS]. Giving up is reported to the user and leaves
 * the local file untouched — a recording that is only on the device is recoverable; one that is silently
 * absent from both places is not.
 */
class RecordingCopyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val src = inputData.getString(KEY_SRC)?.toUri() ?: return Result.failure()
        val destFolder = inputData.getString(KEY_DEST_FOLDER)?.toUri() ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: DEFAULT_MIME
        val deleteLocal = inputData.getBoolean(KEY_DELETE_LOCAL, false)
        val isSidecar = inputData.getBoolean(KEY_IS_SIDECAR, false)

        if (!SafHelper.isFolderValid(applicationContext, destFolder)) {
            return giveUpOrRetry(name, "the Drive folder is not reachable or writable", isSidecar)
        }

        val srcSize = SafHelper.fileSize(applicationContext, src)
        if (srcSize <= 0L) {
            // Either capture produced nothing or the source is gone. Uploading it would put an unplayable
            // file in the user's Drive and mark the recording as backed up; refusing is the honest answer.
            // This is also a terminal answer, not a retry: a source that is empty now stays empty, and
            // retrying one forever is what kept re-uploading recordings hours after the call.
            val gone = runCatching { DocumentFile.fromSingleUri(applicationContext, src)?.exists() != true }
                .getOrDefault(false)
            val what = if (gone) "the source file is gone" else "the source is empty (size=$srcSize)"
            AppLogger.e(TAG, "Not copying '$name' to Drive: $what")
            return Result.failure()
        }

        return when (val result = SafHelper.copyFileToFolder(applicationContext, src, destFolder, name, mime, srcSize)) {
            is SafHelper.CopyResult.AlreadyPresent -> {
                if (FolderOverlap.isSameDocument(src, result.uri)) {
                    // The "Drive" folder is the recording folder: the copy we found is the recording itself.
                    // Deleting it now would delete the only copy (Xiaomi report, 2026-11-04). Keep it.
                    AppLogger.e(TAG, "'$name': the Drive folder is the recording folder; keeping the device copy, nothing uploaded")
                    return Result.success()
                }
                AppLogger.i(TAG, "'$name' is already in Drive; not uploading it again")
                finish(name, result.uri, srcSize, deleteLocal, src, isSidecar)
            }

            is SafHelper.CopyResult.Copied -> {
                AppLogger.i(TAG, "Recording copied to Drive ('$name', ${result.bytes} bytes, deleteLocal=$deleteLocal)")
                finish(name, result.uri, result.bytes, deleteLocal, src, isSidecar)
            }

            is SafHelper.CopyResult.Failed -> giveUpOrRetry(name, result.reason, isSidecar)
        }
    }

    /** Records the Drive copy on the catalog row and drops the local source when the target is cloud-only. */
    private suspend fun finish(
        name: String,
        driveUri: Uri,
        sizeBytes: Long,
        deleteLocal: Boolean,
        src: Uri,
        isSidecar: Boolean
    ): Result {
        // SAF providers can refuse deletion. Do not remove the local catalog
        // reference unless deletion actually completed; otherwise the UI loses
        // track of a recording that still exists on the phone.
        val localDocument = if (deleteLocal) {
            DocumentFile.fromSingleUri(applicationContext, src)
        } else null
        val deletedLocally = deleteLocal && localDocument != null &&
            SafHelper.deleteDocument(localDocument, "the device copy of '$name'")
        // A sidecar (.json/.md) has no catalog row of its own — it belongs to its recording, which was
        // stamped when the audio uploaded — so there is nothing to mark. Only the audio copy updates the
        // catalog, so the Home list reflects where the file now lives without re-scanning the Drive folder.
        if (!isSidecar) {
            RecordingCatalog.markDrive(applicationContext, name, driveUri, sizeBytes.takeIf { it > 0L },
                deleteLocalAfter = CloudCopyPolicy.shouldClearLocalReference(deleteLocal, deletedLocally))
        }
        return Result.success()
    }

    /**
     * Asks for another retry while the budget lasts, and reports an honest failure once it is spent —
     * rather than retrying until the end of time, which is what filled Drive with duplicates.
     */
    private fun giveUpOrRetry(name: String, reason: String, isSidecar: Boolean): Result {
        if (!CloudCopyPolicy.isLastAttempt(runAttemptCount)) {
            AppLogger.w(TAG, "Copy of '$name' to Drive failed ($reason); retrying (attempt ${runAttemptCount + 1})")
            return Result.retry()
        }
        AppLogger.e(TAG, "Giving up on copying '$name' to Drive after ${runAttemptCount + 1} attempts ($reason). The device copy is kept.")
        // Only the audio copy is worth interrupting the user over. A sidecar that misses Drive is a
        // details/transcript file whose recording is already backed up; log it and stay quiet.
        if (!isSidecar) {
            runCatching {
                RecordingNotificationHelper(applicationContext)
                    .showErrorNotification(applicationContext.getString(R.string.recording_error_drive_copy_failed))
            }.onFailure { AppLogger.w(TAG, "Could not warn about the failed Drive copy: ${it.message}") }
        }
        return Result.failure()
    }

    companion object {
        private const val TAG = "CV:RecordingCopyWorker"
        private const val DEFAULT_MIME = "audio/ogg"
        const val KEY_SRC = "srcUri"
        const val KEY_DEST_FOLDER = "destFolderUri"
        const val KEY_NAME = "displayName"
        const val KEY_MIME = "mimeType"
        const val KEY_DELETE_LOCAL = "deleteLocalAfter"
        const val KEY_IS_SIDECAR = "isSidecar"
    }
}
