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
     * Best-effort, off the broadcast thread: re-grant WRITE_SECURE_SETTINGS that the install-over
     * dropped, over any transport that's ALREADY up (WD still on / loopback armed) — no adbd churn.
     *
     * [AdbShell.tryHealWriteSecureSettings] is used INSTEAD of relying on the daemon launcher, because
     * when the daemon survived the update [RecorderServerLauncher.ensureServerRunning] early-returns on
     * the already-connected binder and never reaches the self-grant — which is exactly the common case
     * (recording keeps working, but the grant stays lost). After healing we still ensure the daemon is
     * up (a no-op when it's already connected). Skipped when the grant survived (in-app updater re-grants
     * inline). Harmless no-op when no transport is up — Home's banner then guides the one-time WD toggle.
     */
    private fun recoverAfterReplace(context: Context) {
        if (AdbShell.hasWriteSecureSettings(context)) {
            AppLogger.d(TAG, "WRITE_SECURE_SETTINGS survived the update; no post-replace recovery needed")
            return
        }
        AppLogger.i(TAG, "WRITE_SECURE_SETTINGS lost on update; attempting non-churning self-heal")
        Thread {
            val healed = runCatching { AdbShell.tryHealWriteSecureSettings(context) }.getOrDefault(false)
            runCatching { RecorderServerLauncher.ensureServerRunning(context) }
            AppLogger.i(TAG, "Post-replace recovery: WRITE_SECURE_SETTINGS regranted=$healed")
        }.apply { isDaemon = true; name = "cv-post-update-recover" }.start()
    }

    companion object {
        private const val TAG = "CV:UpdateReplacedRecv"
    }
}
