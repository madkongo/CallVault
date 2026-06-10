/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kitsumed.shizucallrecorder.utils.AppLogger

/** Copies a finished recording from the local SAF folder to the Drive SAF folder, verifies by
 *  size, optionally deletes the local copy, and retries (WorkManager backoff) on failure. */
class RecordingCopyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val src = inputData.getString(KEY_SRC)?.toUri() ?: return Result.failure()
        val destFolder = inputData.getString(KEY_DEST_FOLDER)?.toUri() ?: return Result.failure()
        val name = inputData.getString(KEY_NAME) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: "audio/ogg"
        val deleteLocal = inputData.getBoolean(KEY_DELETE_LOCAL, false)

        if (!SafHelper.isFolderValid(applicationContext, destFolder)) return Result.retry()
        val srcSize = SafHelper.fileSize(applicationContext, src)
        val copied = SafHelper.copyFileToFolder(applicationContext, src, destFolder, name, mime)
            ?: return Result.retry()
        val verified = srcSize <= 0L || SafHelper.fileSize(applicationContext, copied) == srcSize
        if (!verified) {
            runCatching { DocumentFile.fromSingleUri(applicationContext, copied)?.delete() }
            AppLogger.w(TAG, "Drive copy size mismatch; will retry")
            return Result.retry()
        }
        if (deleteLocal) runCatching { DocumentFile.fromSingleUri(applicationContext, src)?.delete() }
        AppLogger.i(TAG, "Recording copied to Drive folder (deleteLocal=$deleteLocal)")
        return Result.success()
    }

    companion object {
        private const val TAG = "SCR:RecordingCopyWorker"
        const val KEY_SRC = "srcUri"
        const val KEY_DEST_FOLDER = "destFolderUri"
        const val KEY_NAME = "displayName"
        const val KEY_MIME = "mimeType"
        const val KEY_DELETE_LOCAL = "deleteLocalAfter"
    }
}
