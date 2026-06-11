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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/** Routes a finished recording to the configured destination. LOCAL = no-op (already on device).
 *  For DRIVE/BOTH the cadence depends on the sync schedule: IMMEDIATE enqueues a per-recording copy to
 *  the Drive SAF folder (DRIVE also deletes local); DAILY/WEEKLY leave the file on device and rely on the
 *  scheduled [SyncSweepWorker] (ensured via [SyncScheduler.apply]) to batch-copy it later. */
object StorageRouter {
    private const val TAG = "CV:StorageRouter"

    fun route(context: Context, localUri: Uri, displayName: String, mimeType: String) {
        val prefs = AppPreferences(context)
        val target = prefs.getStorageTarget()
        if (target == StorageTarget.LOCAL) return
        val driveFolder = prefs.getDriveFolderUri()
        if (driveFolder == null) {
            AppLogger.w(TAG, "Storage target is $target but no Drive folder is configured; keeping local copy only.")
            return
        }

        // DAILY/WEEKLY: don't copy now — ensure the periodic sweep is scheduled and leave the file on device.
        if (prefs.getSyncScheduleMode() != SyncScheduleMode.IMMEDIATE) {
            SyncScheduler.apply(context)
            AppLogger.i(TAG, "Sync schedule is ${prefs.getSyncScheduleMode()}; deferring copy of '$displayName' to the periodic sweep.")
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
