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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.utils.AppLogger

/**
 * Daily sweep that permanently deletes recordings older than the configured retention period. Reads the
 * Room catalog (the source of truth for the Home list) and applies retention PER COPY: device copies use
 * [AppPreferences.getRetentionLocalDays], Drive copies use [AppPreferences.getRetentionDriveDays] (0 =
 * keep forever). Age is measured from each entry's recorded timestamp ([lastModified]).
 *
 * Deletion goes through [RecordingsRepository.deleteFile], which removes the SAF file AND clears that copy
 * from the catalog (dropping the row once no copy remains). Robust by design: entries with an unknown age
 * are never deleted; a failed delete (e.g. Drive offline) is simply retried on the next daily run.
 */
class RetentionSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        val localDays = prefs.getRetentionLocalDays()
        val driveDays = prefs.getRetentionDriveDays()
        if (localDays <= 0 && driveDays <= 0) {
            AppLogger.i(TAG, "Retention off; nothing to sweep.")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val localCutoff = if (localDays > 0) now - localDays * DAY_MS else null
        val driveCutoff = if (driveDays > 0) now - driveDays * DAY_MS else null

        var deletedLocal = 0
        var deletedDrive = 0
        for (entry in RecordingCatalog.all(applicationContext)) {
            val ts = entry.lastModified
            if (ts <= 0L) continue // unknown age — never auto-delete

            val localUri = entry.localUri
            if (localCutoff != null && localUri != null && ts < localCutoff) {
                if (RecordingsRepository.deleteFile(applicationContext, localUri.toUri())) deletedLocal++
            }
            val driveUri = entry.driveUri
            if (driveCutoff != null && driveUri != null && ts < driveCutoff) {
                if (RecordingsRepository.deleteFile(applicationContext, driveUri.toUri())) deletedDrive++
            }
        }

        AppLogger.i(TAG, "Retention sweep complete (deletedLocal=$deletedLocal deletedDrive=$deletedDrive).")
        return Result.success()
    }

    companion object {
        private const val TAG = "CV:RetentionSweep"
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
