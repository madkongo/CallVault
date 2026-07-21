/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user-initiated install (Home banner "Update"). Runs in WorkManager rather than a ViewModel
 * scope so the 80+ MB download and install SURVIVE the user navigating away or a config change —
 * the outcome is reported through the updater's notifications, and the Home banner spinner is driven
 * by this work's observable state.
 *
 * [UpdateManager.checkForUpdate] is called with reconcile=false: the user is actively retrying, so a
 * stale failure from a previous attempt must not surface a scary notification mid-retry.
 */
class UpdateInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val release = UpdateManager.checkForUpdate(applicationContext, reconcile = false)
            ?: return@withContext Result.success()
        UpdateManager.downloadAndInstall(applicationContext, release, allowInteractiveFallback = true) { percent ->
            // Publish to WorkManager so the Home banner can show the download percentage.
            setProgressAsync(workDataOf(KEY_PROGRESS to percent))
        }
        Result.success()
    }

    companion object {
        /** WorkManager progress key: download percentage (0-100). */
        const val KEY_PROGRESS = "progress_percent"
    }
}
