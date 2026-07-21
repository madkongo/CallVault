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
     * @param reconcile when true, first reports any earlier install that never landed as a failure.
     *                  The user-initiated (banner) path passes false: it is about to retry, so a
     *                  stale failure from the previous attempt must not pop up mid-retry.
     * @return the newer release when one exists, else null.
     */
    fun checkForUpdate(context: Context, reconcile: Boolean = true): GitHubReleases.ReleaseInfo? {
        val preferences = AppPreferences(context)
        if (reconcile) reconcilePendingState(context, preferences)

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
     *
     * Both paths install through the privileged shell first (silent, no dialog — CallVault's
     * advantage). [allowInteractiveFallback] governs what happens when the shell can't come up:
     * the user-initiated (banner) path falls back to a PackageInstaller session (which may show the
     * system confirm dialog); the unattended auto path instead degrades to the "update available"
     * notification, since there is no user present to answer a dialog.
     *
     * @return true when an install was dispatched (final outcome arrives via notifications).
     */
    fun downloadAndInstall(
        context: Context,
        release: GitHubReleases.ReleaseInfo,
        allowInteractiveFallback: Boolean
    ): Boolean {
        val preferences = AppPreferences(context)
        val apk = downloadFile(context)

        if (!GitHubReleases.downloadApk(release, apk)) {
            if (allowInteractiveFallback) {
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
        return when (UpdateInstaller.installSilentlyViaShell(context, apk)) {
            UpdateInstaller.ShellResult.DISPATCHED -> true
            UpdateInstaller.ShellResult.FAILED -> {
                preferences.setPendingUpdateTag(null)
                UpdateNotifications.showUpdateFailure(
                    context, context.getString(R.string.update_notif_failure_install_text)
                )
                false
            }
            UpdateInstaller.ShellResult.UNAVAILABLE -> {
                if (allowInteractiveFallback && UpdateInstaller.installViaPackageInstaller(context, apk)) {
                    true
                } else {
                    preferences.setPendingUpdateTag(null)
                    // No shell and no interactive path: leave the "update available" notification so
                    // the user can still install manually.
                    notifyAvailableOnce(context, preferences, release.tag)
                    false
                }
            }
        }
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
