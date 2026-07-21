/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import android.net.ConnectivityManager
import com.baba.callvault.BuildConfig
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * Orchestrates the in-app update flow shared by the periodic worker and the Home banner:
 *
 * - [checkForUpdate]: cheap JSON query; records an available tag and (manual mode) notifies once.
 * - [downloadAndInstall]: download → verify (pinned cert) → install, silently (auto mode, via the
 *   privileged shell) or interactively (PackageInstaller). The 80+ MB APK is never auto-downloaded
 *   on a metered connection — auto mode defers to the "update available" notification there.
 * - [reconcilePendingState]: an install that was fired but never landed (version unchanged on the
 *   next run) is reported as a failure exactly once.
 */
object UpdateManager {

    private const val TAG = "CV:UpdateManager"
    private const val DOWNLOAD_DIR = "updates"
    private const val DOWNLOAD_NAME = "CallVault-update.apk"

    /** Where the downloaded (not yet verified) APK lands, inside app-private cache. */
    fun downloadFile(context: Context): File =
        File(File(context.cacheDir, DOWNLOAD_DIR), DOWNLOAD_NAME)

    fun cleanupDownloadCache(context: Context) {
        runCatching { File(context.cacheDir, DOWNLOAD_DIR).deleteRecursively() }
    }

    /**
     * Queries the latest release and updates local state. Blocking network I/O — worker thread only.
     * @return the newer release when one exists, else null.
     */
    fun checkForUpdate(context: Context): GitHubReleases.ReleaseInfo? {
        val preferences = AppPreferences(context)
        reconcilePendingState(context, preferences)

        val release = GitHubReleases.fetchLatestRelease() ?: return null
        if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
            // Up to date (or ahead, e.g. a local test build): clear any stale banner state.
            preferences.setAvailableUpdateTag(null)
            return null
        }

        AppLogger.i(TAG, "Update available: ${release.tag} (installed ${BuildConfig.VERSION_NAME})")
        preferences.setAvailableUpdateTag(release.tag)
        return release
    }

    /**
     * Downloads, verifies, and installs [release]. Blocking — worker thread only.
     * @param silent true = auto-update path (shell install, no user action); false = the user asked
     *               for this install (PackageInstaller; the system may show its confirm dialog).
     * @return true when an install was dispatched (outcome arrives via notifications).
     */
    fun downloadAndInstall(context: Context, release: GitHubReleases.ReleaseInfo, silent: Boolean): Boolean {
        val preferences = AppPreferences(context)
        val apk = downloadFile(context)

        if (!GitHubReleases.downloadApk(release, apk)) {
            if (!silent) {
                UpdateNotifications.showUpdateFailure(
                    context, context.getString(R.string.update_notif_failure_download_text)
                )
            }
            return false
        }
        if (!ApkVerifier.isValidUpdate(context, apk)) {
            cleanupDownloadCache(context)
            UpdateNotifications.showUpdateFailure(
                context, context.getString(R.string.update_notif_failure_verify_text)
            )
            return false
        }

        preferences.setPendingUpdateTag(release.tag)
        val dispatched = if (silent) {
            UpdateInstaller.installSilentlyViaShell(context, apk)
        } else {
            UpdateInstaller.installViaPackageInstaller(context, apk)
        }
        if (!dispatched) {
            preferences.setPendingUpdateTag(null)
            if (silent) {
                // Shell unavailable (e.g. Developer options off): degrade to the manual flow.
                notifyAvailableOnce(context, preferences, release.tag)
            } else {
                UpdateNotifications.showUpdateFailure(
                    context, context.getString(R.string.update_notif_failure_install_text)
                )
            }
        }
        return dispatched
    }

    /** Posts the "update available" notification at most once per tag. */
    fun notifyAvailableOnce(context: Context, preferences: AppPreferences, tag: String) {
        if (preferences.getLastNotifiedUpdateTag() == tag) return
        UpdateNotifications.showUpdateAvailable(context, tag)
        preferences.setLastNotifiedUpdateTag(tag)
    }

    /** True when the active network is metered (auto mode won't download the APK on it). */
    fun isNetworkMetered(context: Context): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(true)

    /**
     * An install was dispatched earlier but the app is still on the same version — the install
     * failed out-of-band (shell `pm install` rejected, user dismissed the confirm dialog, reboot
     * mid-install). Reports the failure once and clears the flag so the user can retry.
     */
    private fun reconcilePendingState(context: Context, preferences: AppPreferences) {
        val pendingTag = preferences.getPendingUpdateTag() ?: return
        if (UpdateVersion.isNewer(pendingTag, BuildConfig.VERSION_NAME)) {
            AppLogger.w(TAG, "Pending update $pendingTag never landed (still on ${BuildConfig.VERSION_NAME}); reporting failure")
            preferences.setPendingUpdateTag(null)
            UpdateNotifications.showUpdateFailure(
                context, context.getString(R.string.update_notif_failure_install_text)
            )
        } else {
            preferences.setPendingUpdateTag(null)
        }
    }
}
