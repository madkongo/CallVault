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
import com.baba.callvault.system.AppForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Update check (scheduled daily by [UpdateScheduler], on app open, and on backgrounding):
 *  - auto-update ON + unmetered + app BACKGROUNDED → download, verify, and install silently;
 *  - auto-update ON but app in the FOREGROUND       → defer (installing kills the app the user is in);
 *    the check still records the pending update, and [AppForeground]'s onBackground hook re-runs this
 *    worker the moment the user leaves the app;
 *  - auto-update OFF                                → notify once per tag; the Home banner offers install.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // CoroutineWorker.doWork runs on Dispatchers.Default; the update work does blocking network +
    // ADB I/O, so move it to Dispatchers.IO to keep it off the CPU-bound thread pool.
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = AppPreferences(applicationContext)
        if (!preferences.isUpdateCheckEnabled()) return@withContext Result.success()

        preferences.setLastUpdateCheckMillis(System.currentTimeMillis())
        val release = UpdateManager.checkForUpdate(applicationContext) ?: return@withContext Result.success()

        val autoUnmetered = preferences.isAutoUpdateEnabled() &&
            !UpdateManager.isNetworkMetered(applicationContext)
        when {
            autoUnmetered && !AppForeground.isForeground -> {
                // Backgrounded auto-update: install silently (relaunchUi=false → no foreground steal);
                // result reported by notification, no interactive fallback (no user present).
                UpdateManager.downloadAndInstall(
                    applicationContext, release, allowInteractiveFallback = false, relaunchUi = false
                )
            }
            autoUnmetered -> {
                // Auto-update wanted but the user is IN the app — defer so we don't kill it mid-use.
                // availableUpdateTag is already recorded; onBackground re-runs this worker on exit.
            }
            else -> UpdateManager.notifyAvailableOnce(applicationContext, preferences, release.tag)
        }
        Result.success()
    }
}
