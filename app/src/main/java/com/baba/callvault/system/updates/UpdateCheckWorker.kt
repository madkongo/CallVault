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

/**
 * Daily update check (scheduled by [UpdateScheduler], network-constrained):
 *  - auto-update ON + unmetered network → download, verify, and install silently;
 *  - auto-update ON + metered network   → don't burn data: notify "update available" instead;
 *  - auto-update OFF                    → notify once per tag; the Home banner offers the install.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val preferences = AppPreferences(applicationContext)
        if (!preferences.isUpdateCheckEnabled()) return Result.success()

        val release = UpdateManager.checkForUpdate(applicationContext) ?: return Result.success()

        if (preferences.isAutoUpdateEnabled() && !UpdateManager.isNetworkMetered(applicationContext)) {
            UpdateManager.downloadAndInstall(applicationContext, release, silent = true)
        } else {
            UpdateManager.notifyAvailableOnce(applicationContext, preferences, release.tag)
        }
        return Result.success()
    }
}
