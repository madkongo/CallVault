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
import android.os.SystemClock
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
            DriveCopyJournal.record(applicationContext, "'$name' not copied after the call: $what")
            return Result.failure()
        }

        // In the Drive copier process, like the sweep (see [DriveCopyService]): Drive's process dying must not
        // take the app with it. Not "Stop for now": that button belongs to the sweep's notification.
        val copier = DriveCopyClient(applicationContext, honorStop = false)
        val started = SystemClock.elapsedRealtime()
        val outcome = try {
            copier.call(
                DriveCopyProtocol.MSG_COPY,
                DriveCopyProtocol.copyRequest(
                    DriveCopyProtocol.Request(src, destFolder, name, mime, srcSize, null, -1L, null, lookup = true),
                ),
                CloudCopyPolicy.fileTimeoutMs(srcSize),
            )
        } finally {
            copier.close()
        }
        var driveSize = -1L
        val copyResult = when (outcome) {
            is DriveCopyClient.Outcome.Reply -> DriveCopyProtocol.readCopyReply(outcome.data).let { (r, steps, size) ->
                driveSize = size
                DriveCopyJournal.record(
                    applicationContext,
                    "copy '$name' ${srcSize}B total=${SystemClock.elapsedRealtime() - started}ms $steps -> ${r.javaClass.simpleName}" +
                        (if (r is SafHelper.CopyResult.Failed) " (${r.reason})" else "") + " (after the call, attempt ${runAttemptCount + 1}, deleteLocal=$deleteLocal)",
                )
                r
            }
            else -> SafHelper.CopyResult.Failed(
                if (outcome is DriveCopyClient.Outcome.TimedOut) "Drive gave no answer in time" else "the Drive copier process died",
            ).also {
                DriveCopyJournal.record(applicationContext, "copy '$name' ${srcSize}B FAILED after ${SystemClock.elapsedRealtime() - started}ms: ${it.reason} (after the call, attempt ${runAttemptCount + 1})")
            }
        }
        return when (val result = copyResult) {
            is SafHelper.CopyResult.AlreadyPresent -> {
                if (FolderOverlap.isSameDocument(src, result.uri)) {
                    // The "Drive" folder is the recording folder: the copy we found is the recording itself.
                    // Deleting it now would delete the only copy (Xiaomi report, 2026-11-04). Keep it.
                    AppLogger.e(TAG, "'$name': the Drive folder is the recording folder; keeping the device copy, nothing uploaded")
                    DriveCopyJournal.record(applicationContext, "'$name': the Drive folder is the recording folder; device copy kept, nothing uploaded")
                    return Result.success()
                }
                AppLogger.i(TAG, "'$name' is already in Drive; not uploading it again")
                finish(name, result.uri, deleteLocal, src, isSidecar, driveSize)
            }

            is SafHelper.CopyResult.Copied -> {
                AppLogger.i(TAG, "Recording copied to Drive ('$name', ${result.bytes} bytes, deleteLocal=$deleteLocal)")
                finish(name, result.uri, deleteLocal, src, isSidecar, driveSize)
            }

            is SafHelper.CopyResult.Failed -> giveUpOrRetry(name, result.reason, isSidecar)
        }
    }

    /** Records the Drive copy on the catalog row and drops the local source when the target is cloud-only. */
    private suspend fun finish(
        name: String,
        driveUri: Uri,
        deleteLocal: Boolean,
        src: Uri,
        isSidecar: Boolean,
        driveSize: Long,
    ): Result {
        // SAF providers can refuse deletion. Do not remove the local catalog
        // reference unless deletion actually completed; otherwise the UI loses
        // track of a recording that still exists on the phone.
        //
        // And the device copy goes only when Drive itself reported a document of the same length: a copy call
        // that returned is not proof (see [CloudCopyPolicy.mayDeleteLocal]). The sidecar's size is the source's.
        val verified = CloudCopyPolicy.mayDeleteLocal(driveSize, SafHelper.fileSize(applicationContext, src))
        val localDocument = if (deleteLocal && verified) {
            DocumentFile.fromSingleUri(applicationContext, src)
        } else null
        val deletedLocally = deleteLocal && localDocument != null &&
            SafHelper.deleteDocument(localDocument, "the device copy of '$name'")
        if (deleteLocal) {
            DriveCopyJournal.record(
                applicationContext,
                "device copy of '$name' " + when {
                    deletedLocally -> "DELETED after Drive reported $driveSize bytes (after the call)"
                    !verified -> "KEPT: Drive reports $driveSize bytes, which does not match the device file (not verified; after the call)"
                    else -> "KEPT: the provider refused the delete (after the call)"
                },
            )
        }
        // A sidecar (.json/.md) has no catalog row of its own — it belongs to its recording, which was
        // stamped when the audio uploaded — so there is nothing to mark. Only the audio copy updates the
        // catalog, so the Home list reflects where the file now lives without re-scanning the Drive folder.
        if (!isSidecar) {
            // Drive's own length, or none: the device size here would let retention treat an unconfirmed upload
            // as proof the call is in Drive (review C7).
            val recorded = CloudCopyPolicy.driveSizeToRecord(driveSize)
            if (recorded == null) {
                AppLogger.w(TAG, "'$name': Drive reported no size for its copy; recorded without one, so the device copy is not yet treated as backed up")
            }
            RecordingCatalog.markDrive(applicationContext, name, driveUri, recorded,
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
            DriveCopyJournal.record(applicationContext, "'$name' after the call: failed ($reason); will retry (attempt ${runAttemptCount + 1} of ${CloudCopyPolicy.MAX_ATTEMPTS})")
            return Result.retry()
        }
        AppLogger.e(TAG, "Giving up on copying '$name' to Drive after ${runAttemptCount + 1} attempts ($reason). The device copy is kept.")
        DriveCopyJournal.record(applicationContext, "'$name' after the call: GAVE UP after ${runAttemptCount + 1} attempts ($reason); the device copy is kept")
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
