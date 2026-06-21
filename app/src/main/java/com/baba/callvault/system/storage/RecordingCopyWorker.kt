/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.utils.AppLogger

/** Copies a finished recording from the local SAF folder to the Drive SAF folder, optionally
 *  deletes the local copy, and retries (WorkManager backoff) only if the copy itself fails. */
class RecordingCopyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val src = inputData.getString(KEY_SRC)?.toUri() ?: return Result.failure()
        val destFolder = inputData.getString(KEY_DEST_FOLDER)?.toUri() ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: "audio/ogg"
        val deleteLocal = inputData.getBoolean(KEY_DELETE_LOCAL, false)

        if (!SafHelper.isFolderValid(applicationContext, destFolder)) return Result.retry()
        val srcSize = SafHelper.fileSize(applicationContext, src)

        // copyFileToFolder streams the whole file and only returns non-null when copyTo() finished
        // without throwing — that IS the success signal. We deliberately do NOT verify by comparing
        // DocumentFile.length(): cloud providers (Google Drive) commonly report length()=0/-1 right
        // after a streamed write, which previously caused a delete-and-retry loop (the file kept
        // disappearing). A size difference is logged as a soft warning, not treated as failure.
        val copied = SafHelper.copyFileToFolder(applicationContext, src, destFolder, name, mime)
            ?: return Result.retry()
        val destSize = SafHelper.fileSize(applicationContext, copied)
        if (srcSize > 0L && destSize > 0L && destSize != srcSize) {
            AppLogger.w(TAG, "Drive copy size differs (src=$srcSize dest=$destSize); keeping it anyway")
        }
        if (deleteLocal) runCatching { DocumentFile.fromSingleUri(applicationContext, src)?.delete() }
        // Stamp the Drive copy onto the catalog row (clearing the local copy when DRIVE-only deletes it),
        // so the Home list reflects where the file now lives without re-scanning the Drive folder.
        RecordingCatalog.markDrive(applicationContext, name, copied, destSize.takeIf { it > 0L }, deleteLocal)
        AppLogger.i(TAG, "Recording copied to Drive (deleteLocal=$deleteLocal, src=$srcSize dest=$destSize)")
        return Result.success()
    }

    companion object {
        private const val TAG = "CV:RecordingCopyWorker"
        const val KEY_SRC = "srcUri"
        const val KEY_DEST_FOLDER = "destFolderUri"
        const val KEY_NAME = "displayName"
        const val KEY_MIME = "mimeType"
        const val KEY_DELETE_LOCAL = "deleteLocalAfter"
    }
}
