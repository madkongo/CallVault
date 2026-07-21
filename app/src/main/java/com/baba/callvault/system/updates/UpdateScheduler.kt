/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Ensures the daily [UpdateCheckWorker] matches the "check for updates" preference. Mirrors the
 * idempotent [com.baba.callvault.system.storage.RetentionScheduler] pattern; call from
 * Application.onCreate and whenever the preference changes.
 */
object UpdateScheduler {

    private const val TAG = "CV:UpdateScheduler"
    private const val WORK_NAME = "cv_update_check"
    private const val PERIOD_HOURS = 24L

    fun apply(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!AppPreferences(context).isUpdateCheckEnabled()) {
            workManager.cancelUniqueWork(WORK_NAME)
            AppLogger.d(TAG, "Update checks disabled; periodic work cancelled")
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
