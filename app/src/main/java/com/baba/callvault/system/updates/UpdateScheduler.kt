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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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

    /** Unique name of the user-initiated install work; the Home banner observes its state. */
    const val INSTALL_WORK_NAME = "cv_update_install"

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

    /**
     * Enqueues the one-time user-initiated install (Home banner "Update"). Unique + KEEP so repeated
     * taps don't stack; requires a network connection. Runs in WorkManager so it outlives the UI.
     */
    fun enqueueInstallNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateInstallWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(INSTALL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
