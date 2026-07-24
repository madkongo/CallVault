/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.server.RecorderServerLauncher
import com.baba.callvault.utils.AppLogger

/**
 * Fires after THIS app was replaced by an update. When the updater initiated that install (the
 * pending tag is set), posts the success notification and clears all updater state. Updates
 * installed by other means (manual sideload, Obtainium) simply clear any stale state silently.
 *
 * **WRITE_SECURE_SETTINGS self-heal (the 1.4.0 incremental-update incident).** An install-over
 * (Obtainium / manual sideload — NOT the in-app updater, which re-grants inline) DROPS the app's
 * runtime WRITE_SECURE_SETTINGS grant. Without it the app can't re-enable Wireless debugging, so it
 * can never reconnect ADB to relaunch the recorder daemon → recording silently dies until a clean
 * reinstall. We recover WITHOUT a reinstall: right after replacement a transport often still
 * survives — loopback (offline mode; `service.adb.tcp.port` persists) or Wireless debugging that
 * wasn't turned off yet — and [RecorderServerLauncher.ensureServerRunning] → [AdbShell.ensureConnected]
 * self-grants WRITE_SECURE_SETTINGS over that shell (`pm grant`) and relaunches the daemon. When no
 * transport survives (WD off + offline mode off) the app can't self-heal here; Home then surfaces the
 * `UPDATE_REGRANT_NEEDED` status telling the user to toggle Wireless debugging on once.
 */
class UpdatePackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = AppPreferences(context)
        val pendingTag = preferences.getPendingUpdateTag()
        AppLogger.i(TAG, "Package replaced; now ${BuildConfig.VERSION_NAME} (pending update tag: $pendingTag)")

        if (pendingTag != null) {
            UpdateNotifications.showUpdateSuccess(context, BuildConfig.VERSION_NAME)
        }
        preferences.setPendingUpdateTag(null)
        preferences.setAvailableUpdateTag(null)
        preferences.setLastNotifiedUpdateTag(null)
        UpdateNotifications.cancelAvailable(context)
        UpdateManager.cleanupDownloadCache(context)

        recoverAfterReplace(context.applicationContext)
    }

    /**
     * Best-effort, off the broadcast thread: reconnect over any surviving transport so
     * [AdbShell.ensureConnected] re-grants WRITE_SECURE_SETTINGS (`pm grant`) and the daemon relaunches.
     * Skipped when the grant already survived (in-app updater re-grants inline). Harmless no-op when no
     * transport survives — the Home `UPDATE_REGRANT_NEEDED` banner then guides the one-time WD toggle.
     */
    private fun recoverAfterReplace(context: Context) {
        if (AdbShell.hasWriteSecureSettings(context)) {
            AppLogger.d(TAG, "WRITE_SECURE_SETTINGS survived the update; no post-replace recovery needed")
            return
        }
        AppLogger.i(TAG, "WRITE_SECURE_SETTINGS lost on update; attempting reconnect self-heal + daemon relaunch")
        Thread {
            runCatching { RecorderServerLauncher.ensureServerRunning(context) }
                .onSuccess { ok ->
                    val healed = AdbShell.hasWriteSecureSettings(context)
                    AppLogger.i(TAG, "Post-replace recovery: daemon=$ok, WRITE_SECURE_SETTINGS regranted=$healed")
                }
                .onFailure { AppLogger.w(TAG, "Post-replace recovery failed (no surviving transport?): ${it.message}") }
        }.apply { isDaemon = true; name = "cv-post-update-recover" }.start()
    }

    companion object {
        private const val TAG = "CV:UpdateReplacedRecv"
    }
}
