/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.system.storage

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.StorageTarget
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.util.concurrent.TimeUnit

/** Routes a finished recording to the configured destination. LOCAL = no-op (already on device);
 *  DRIVE/BOTH enqueue a WorkManager job to copy it to the Drive SAF folder (DRIVE also deletes local). */
object StorageRouter {
    private const val TAG = "SCR:StorageRouter"

    fun route(context: Context, localUri: Uri, displayName: String, mimeType: String) {
        val prefs = AppPreferences(context)
        val target = prefs.getStorageTarget()
        if (target == StorageTarget.LOCAL) return
        val driveFolder = prefs.getDriveFolderUri()
        if (driveFolder == null) {
            AppLogger.w(TAG, "Storage target is $target but no Drive folder is configured; keeping local copy only.")
            return
        }
        val data = Data.Builder()
            .putString(RecordingCopyWorker.KEY_SRC, localUri.toString())
            .putString(RecordingCopyWorker.KEY_DEST_FOLDER, driveFolder.toString())
            .putString(RecordingCopyWorker.KEY_NAME, displayName)
            .putString(RecordingCopyWorker.KEY_MIME, mimeType)
            .putBoolean(RecordingCopyWorker.KEY_DELETE_LOCAL, target == StorageTarget.DRIVE)
            .build()
        val request = OneTimeWorkRequestBuilder<RecordingCopyWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        AppLogger.i(TAG, "Enqueued copy-to-Drive for '$displayName' (target=$target)")
    }
}
