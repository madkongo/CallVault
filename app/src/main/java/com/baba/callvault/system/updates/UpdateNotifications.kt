/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.system.permissions.PermissionChecks

/**
 * Notifications for the in-app updater: "update available" (manual mode), and the
 * success/failure result that BOTH modes report after an install attempt.
 */
object UpdateNotifications {

    private const val CHANNEL_ID = "app_updates"

    // 47xx block, following CallMonitorService (4714) and RecorderReadinessNotifier (4715).
    private const val AVAILABLE_NOTIFICATION_ID = 4716
    private const val RESULT_NOTIFICATION_ID = 4717
    private const val PROGRESS_NOTIFICATION_ID = 4718

    /** "vX.Y.Z is available" — tapping opens the app, where the Home banner offers Update. */
    fun showUpdateAvailable(context: Context, tag: String) {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            context, AVAILABLE_NOTIFICATION_ID,
            title = context.getString(R.string.update_notif_available_title, tag.removePrefix("v")),
            text = context.getString(R.string.update_notif_available_text),
            contentIntent = openApp
        )
    }

    /**
     * Ongoing "Downloading update… N%" notification — the only feedback the user has once the app's
     * own UI is torn down by the install, so it's posted for BOTH the auto and manual paths.
     */
    fun showDownloadProgress(context: Context, percent: Int) {
        if (!PermissionChecks.hasNotificationPermission(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.update_notif_downloading_title))
            .setContentText("$percent%")
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    /** Ongoing indeterminate "Installing update…" notification (download done, pm install running). */
    fun showInstalling(context: Context) {
        if (!PermissionChecks.hasNotificationPermission(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.update_notif_installing_title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    fun cancelProgress(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(PROGRESS_NOTIFICATION_ID)
        }
    }

    /** Install confirmed (fires from MY_PACKAGE_REPLACED after a successful update). */
    fun showUpdateSuccess(context: Context, versionName: String) {
        cancelAvailable(context)
        cancelProgress(context)
        post(
            context, RESULT_NOTIFICATION_ID,
            title = context.getString(R.string.update_notif_success_title),
            text = context.getString(R.string.update_notif_success_text, versionName)
        )
    }

    /** Install failed — offers the GitHub releases page as a manual way out. */
    fun showUpdateFailure(context: Context, reason: String) {
        cancelProgress(context)
        val openReleases = PendingIntent.getActivity(
            context, 1,
            Intent(Intent.ACTION_VIEW, Uri.parse(GitHubReleases.RELEASES_PAGE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            context, RESULT_NOTIFICATION_ID,
            title = context.getString(R.string.update_notif_failure_title),
            text = reason,
            contentIntent = openReleases
        )
    }

    fun cancelAvailable(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(AVAILABLE_NOTIFICATION_ID)
        }
    }

    private fun post(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent? = null
    ) {
        if (!PermissionChecks.hasNotificationPermission(context)) return
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        }
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_notif_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
