/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.debug

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.permissions.PermissionChecks

/**
 * Posts (and clears) the persistent reminder that debug logging is currently enabled.
 *
 * Debug logging quietly grows a log file and is meant to be a temporary, "turn it on to reproduce a
 * bug" state — so while it is on we keep an ongoing, low-importance notification visible to nudge the
 * user to turn it back off. Tapping the notification opens the app (where the Debug section's toggle
 * lives).
 */
object DebugNotificationHelper {

    private const val CHANNEL_ID = "debug_logging_channel"
    private const val NOTIFICATION_ID = 4242

    /** Posts the reminder when logging is enabled, or clears it when disabled. Safe to call anytime. */
    fun sync(context: Context) {
        if (AppPreferences(context).isLoggingEnabled()) show(context) else cancel(context)
    }

    /** Shows the ongoing "debug logging is on" reminder. No-op if POST_NOTIFICATIONS is denied. */
    // Permission is verified at runtime via PermissionChecks.hasNotificationPermission() before
    // notify() is called; lint can't trace into the helper, so the check is suppressed here.
    @SuppressLint("MissingPermission")
    fun show(context: Context) {
        createChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.debug_notification_title))
            .setContentText(context.getString(R.string.debug_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.debug_notification_text)))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Skip when POST_NOTIFICATIONS is not granted (Android 13+); the in-app warning banner still
        // informs the user, so we don't force a permission prompt here. Guarding explicitly (rather than
        // relying on notify()'s silent no-op) satisfies the MissingPermission lint check.
        if (!PermissionChecks.hasNotificationPermission(context)) return
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Clears the reminder. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.debug_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
