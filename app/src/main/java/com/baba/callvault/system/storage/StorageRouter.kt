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
import androidx.documentfile.provider.DocumentFile
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
        // An imported file is the user's own audio, not a call we recorded, and under DRIVE the copy
        // is followed by a delete of the local original — so routing one would take their file off
        // the phone. Same gate as the scheduled sweep; see [CloudCopyPolicy.mayGoToCloud].
        if (!CloudCopyPolicy.mayGoToCloud(displayName)) {
            AppLogger.i(TAG, "'$displayName' was imported; leaving it on the device.")
            return
        }
        val prefs = AppPreferences(context)
        val target = prefs.getStorageTarget()
        if (target == StorageTarget.LOCAL) return
        val driveFolder = prefs.getDriveFolderUri()
        if (driveFolder == null) {
            AppLogger.w(TAG, "Storage target is $target but no Drive folder is configured; keeping local copy only.")
            return
        }
        if (FolderOverlap.overlaps(prefs.getRecordingFolderUri(), driveFolder)) {
            AppLogger.e(TAG, "The Drive folder is the recording folder or overlaps it; keeping '$displayName' on the device.")
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

    /**
     * Routes a recording's sidecar (the `.json` details file or the `.md` transcript) to Drive the same
     * way [route] does the audio — but ONLY in [SyncScheduleMode.IMMEDIATE]. DAILY/WEEKLY already copy the
     * whole folder in the scheduled sweep, so the sidecars ride along there; it is only IMMEDIATE that
     * uploads the audio alone and would otherwise leave the sidecars behind (issue #49). Cloud-only
     * deletes the local sidecar after upload, matching the audio, so a DRIVE user is not left with an
     * orphan `.json`/`.md` next to a recording that is gone.
     *
     * [recordingName] is the audio's display name, used only for the import gate (a sidecar name is
     * derived from it); [sidecarName] is the actual file to copy.
     */
    fun routeSidecar(context: Context, recordingName: String, sidecarName: String, mimeType: String) {
        if (!CloudCopyPolicy.mayGoToCloud(recordingName)) return
        val prefs = AppPreferences(context)
        val target = prefs.getStorageTarget()
        if (target == StorageTarget.LOCAL) return
        if (prefs.getSyncScheduleMode() != SyncScheduleMode.IMMEDIATE) return // the sweep handles DAILY/WEEKLY
        val driveFolder = prefs.getDriveFolderUri() ?: return
        val recordingFolder = prefs.getRecordingFolderUri()
        if (FolderOverlap.overlaps(recordingFolder, driveFolder)) return // see route()
        if (recordingFolder == null) {
            AppLogger.w(TAG, "No recording folder; cannot find sidecar '$sidecarName' to copy to Drive.")
            return
        }
        val sidecarUri = runCatching {
            DocumentFile.fromTreeUri(context, recordingFolder)?.findFile(sidecarName)?.uri
        }.getOrNull()
        if (sidecarUri == null) {
            AppLogger.w(TAG, "Sidecar '$sidecarName' not found in the recording folder; nothing to copy.")
            return
        }
        val data = Data.Builder()
            .putString(RecordingCopyWorker.KEY_SRC, sidecarUri.toString())
            .putString(RecordingCopyWorker.KEY_DEST_FOLDER, driveFolder.toString())
            .putString(RecordingCopyWorker.KEY_NAME, sidecarName)
            .putString(RecordingCopyWorker.KEY_MIME, mimeType)
            .putBoolean(RecordingCopyWorker.KEY_DELETE_LOCAL, target == StorageTarget.DRIVE)
            .putBoolean(RecordingCopyWorker.KEY_IS_SIDECAR, true)
            .build()
        val request = OneTimeWorkRequestBuilder<RecordingCopyWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        AppLogger.i(TAG, "Enqueued sidecar copy-to-Drive for '$sidecarName' (target=$target)")
    }
}
