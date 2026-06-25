/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.app.Application
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderServerLauncher
import com.baba.callvault.services.debug.DebugNotificationHelper
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.utils.AppLogger

/**
 * CallVaultApplication is run when the app process is created. Can be seen as the very first entry point of the app.
 */
class CallVaultApplication : Application() {
    private companion object {
        const val TAG = "CV:CallVaultApplication"
    }

    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)

        // Re-assert the "debug logging is on" reminder if the user left logging enabled across an
        // app restart, so the nudge to turn it back off survives process death.
        runCatching { DebugNotificationHelper.sync(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Debug notification sync failed: ${it.message}") }

        // Reconcile the daily retention sweep with the saved prefs (schedules it when retention is on,
        // cancels it when off). Idempotent; ensures the sweep persists across reinstalls/reboots.
        runCatching { RetentionScheduler.apply(applicationContext) }
            .onFailure { AppLogger.w(TAG, "Retention scheduler apply failed: ${it.message}") }

        // If ADB was already paired, proactively bring up the persistent recorder daemon in the
        // background: this (transiently) enables Wireless debugging if needed, launches the daemon,
        // waits for its binder, then turns WD back OFF — so the app is recording-ready over binder and
        // WD is off when idle, with no user action. Best-effort; the call path also ensures it on demand.
        if (AppPreferences(applicationContext).isAdbPaired()) {
            Thread {
                runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }
                    .onFailure { AppLogger.w(TAG, "Startup recorder-daemon warmup failed: ${it.message}") }
            }.apply { isDaemon = true }.start()
        }
    }
}