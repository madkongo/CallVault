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
import com.baba.callvault.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Update check (scheduled daily by [UpdateScheduler] and run on app open). When a newer release
 * exists it records it and notifies once per tag; the Home banner then offers a one-tap install.
 * Installing itself is always an explicit user action (there is no automatic install).
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // CoroutineWorker.doWork runs on Dispatchers.Default; the update work does blocking network I/O,
    // so move it to Dispatchers.IO to keep it off the CPU-bound thread pool.
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = AppPreferences(applicationContext)
        if (!preferences.isUpdateCheckEnabled()) return@withContext Result.success()

        preferences.setLastUpdateCheckMillis(System.currentTimeMillis())
        val release = UpdateManager.checkForUpdate(applicationContext) ?: return@withContext Result.success()
        UpdateManager.notifyAvailableOnce(applicationContext, preferences, release.tag)
        Result.success()
    }
}
