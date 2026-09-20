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
import com.baba.callvault.server.ShizukuBackend
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.server.RecorderBackend
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
     * Puts the app back on its feet after an install-over, off the broadcast thread.
     *
     * **The rule lives in [PostUpdateRecovery] and its invariant is that the recorder always comes
     * back.** This used to return early whenever `WRITE_SECURE_SETTINGS` survived the update, reasoning
     * that there was nothing left to heal — and that reasoning cost a real 13-minute call on
     * 2026-09-06. An update stops our foreground services and kills the privileged daemon whatever
     * happens to the grant; the next call then woke the app, started recording, and wrote nothing
     * because there was no daemon to capture through. Healing the grant was only ever half the job.
     *
     * [AdbShell.tryHealWriteSecureSettings] is used INSTEAD of relying on the daemon launcher, because
     * when the daemon survived the update [RecorderServerLauncher.ensureServerRunning] early-returns on
     * the already-connected binder and never reaches the self-grant. It runs over any transport that is
     * ALREADY up, so it causes no adbd churn, and is a harmless no-op when none is — Home's banner then
     * guides the one-time WD toggle.
     *
     * [RecorderBackend.ensureRunning] is a no-op when the daemon is already connected, and it also
     * starts the keep-alive service, which is the other thing the replace stopped.
     */
    private fun recoverAfterReplace(context: Context) {
        val mode = AppPreferences(context).getPrivilegedMode()
        val grantSurvived = !mode.needsShizuku && AdbShell.hasWriteSecureSettings(context)
        val plan = PostUpdateRecovery.plan(mode, grantSurvived)
        AppLogger.i(TAG, "App replaced (mode=$mode, grant survived=$grantSurvived): $plan")

        // FIRST, and on this thread: the keep-alive is a foreground service, and from Android 12 an app
        // in the background may only start one inside a short exemption window — which MY_PACKAGE_REPLACED
        // grants while the broadcast is being processed. Starting it after the daemon work below (which
        // took ~6 s when measured) would fall outside that window and be refused.
        if (plan.restartKeepAlive) {
            runCatching { DaemonKeepAliveService.start(context) }
                .onFailure { AppLogger.w(TAG, "Could not restart the keep-alive after the replace: ${it.message}") }
        }

        Thread {
            // Shizuku's user service survives the replace holding a path to an APK that no longer
            // exists. It keeps answering, so nothing looks wrong, until a call needs the scrcpy jar and
            // there is nothing to extract it from — measured on the OP9 as a call that recorded 0 bytes
            // and reported success. Shizuku only restarts a service whose version changed, so a
            // same-version reinstall (every development install) needs this too.
            if (plan.restartShizukuService) {
                // NOT a bare `ShizukuBackend.stop(remove = true)`, which is what this was. Shizuku does
                // not kill the process when asked to remove it, a live binder is never dropped by a
                // plain stop, and the ensureRunning below then "reused" the stale service and had it
                // kill the fresh one. Measured on the OP9 on 2026-09-20; the call that followed the
                // update recorded nothing.
                runCatching { RecorderBackend.retireShizukuService("it predates the update that just installed") }
                    .onFailure { AppLogger.w(TAG, "Could not retire the stale Shizuku service: ${it.message}") }
            }
            val healed = if (plan.healGrant) {
                runCatching { AdbShell.tryHealWriteSecureSettings(context) }.getOrDefault(false)
            } else {
                false
            }
            if (plan.ensureRecorder) {
                runCatching { RecorderBackend.ensureRunning(context) }
                    .onFailure { AppLogger.w(TAG, "Post-replace recorder restart failed: ${it.message}") }
            }
            AppLogger.i(TAG, "Post-replace recovery done (grant regranted=$healed)")
        }.apply { isDaemon = true; name = "cv-post-update-recover" }.start()
    }

    companion object {
        private const val TAG = "CV:UpdateReplacedRecv"
    }
}
