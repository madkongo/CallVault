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
    private const val CHECK_NOW_WORK_NAME = "cv_update_check_now"
    private const val PERIOD_HOURS = 24L

    /** Minimum gap between check-on-open triggers, so relaunches can't hammer the GitHub API. */
    private const val CHECK_ON_OPEN_THROTTLE_MS = 6 * 60 * 60 * 1000L

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
     * Runs an immediate one-time check when the app is opened, so a new release surfaces promptly
     * instead of waiting up to [PERIOD_HOURS] for the daily worker. Throttled by
     * [CHECK_ON_OPEN_THROTTLE_MS] (via the last-check timestamp) so frequent relaunches don't spam
     * the GitHub API. No-op when checks are disabled.
     */
    fun checkNowIfDue(context: Context) {
        val preferences = AppPreferences(context)
        if (!preferences.isUpdateCheckEnabled()) return
        val elapsed = System.currentTimeMillis() - preferences.getLastUpdateCheckMillis()
        if (elapsed < CHECK_ON_OPEN_THROTTLE_MS) return

        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CHECK_NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
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

    /**
     * Enqueues an immediate auto-update check+install, used when the app goes to the background with a
     * pending update — so an auto-update installs the moment the user leaves the app, never while they
     * are looking at it. Reuses [UpdateCheckWorker], which installs only when auto-update is on, the
     * network is unmetered, and the app is backgrounded.
     */
    fun enqueueAutoInstall(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CHECK_NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
