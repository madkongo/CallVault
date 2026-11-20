/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.MainActivity
import com.baba.callvault.R
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.utils.AppLogger

/**
 * The recording-folder alert (review N5): the folder is gone, calls are waiting, the waiting area is filling or full.
 *
 * Field report: three calls were lost and the user was told nothing, because the only warning went through the
 * missed-call gate meant for voicemail (issue #29). This is posted directly, on the high-importance "Recording
 * errors" channel, one notification that always says the current state. Tapping it opens Home, whose red card leads
 * to Settings, where the folder is chosen.
 */
sealed interface FolderNotice {
    data object Clear : FolderNotice

    /** Exactly one of [titleRes] and [titlePlural] is set; [count] fills the plural. */
    data class Post(val titleRes: Int?, val titlePlural: Int?, val count: Int, val textRes: Int) : FolderNotice

    companion object {
        private const val TAG = "CV:FolderNotice"

        /** One id for every state, so the notice is replaced rather than stacked. Not used elsewhere (4712-4727 are). */
        const val NOTIFICATION_ID = 4728

        /** What the alert should say now. [refused] is set when a call could not be kept at all. */
        fun decide(
            folderGone: Boolean,
            waitingCount: Int,
            waitingBytes: Long,
            refused: WaitingPolicy.Admission?,
            phoneStorageFull: Boolean = false,
        ): FolderNotice {
            // The phone itself is out of room: say so. "Choose a recording folder" would be wrong advice (review R2).
            if (refused == WaitingPolicy.Admission.NO_SPACE) {
                return Post(R.string.storage_full_title, null, 0, R.string.storage_full_not_recorded_text)
            }
            if (refused != null && refused != WaitingPolicy.Admission.OK) {
                return Post(R.string.recording_folder_gone_title, null, 0, R.string.waiting_calls_full_text)
            }
            if (waitingCount > 0) {
                val text = when (WaitingPolicy.level(waitingBytes)) {
                    WaitingPolicy.Level.NORMAL ->
                        if (phoneStorageFull && !folderGone) R.string.waiting_calls_storage_full_text else R.string.waiting_calls_text
                    WaitingPolicy.Level.NEARLY_FULL -> R.string.waiting_calls_nearly_full_text
                    WaitingPolicy.Level.FULL -> R.string.waiting_calls_full_text
                }
                return Post(null, R.plurals.waiting_calls_title, waitingCount, text)
            }
            if (folderGone) return Post(R.string.recording_folder_gone_title, null, 0, R.string.notif_folder_gone_text)
            return Clear
        }

        /** Shows (or clears) [notice]. Never throws; returns whether something was handed to the system. */
        fun show(context: Context, notice: FolderNotice): Boolean = when (notice) {
            Clear -> {
                runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
                false
            }
            is Post -> {
                val localized = LocalizedContext.of(context)
                val title = notice.titleRes?.let { localized.getString(it) }
                    ?: localized.resources.getQuantityString(notice.titlePlural!!, notice.count, notice.count)
                post(context, title, localized.getString(notice.textRes), NotificationCompat.PRIORITY_HIGH)
            }
        }

        /** [count] waiting calls are in the folder now. Replaces the alert. */
        fun showSaved(context: Context, count: Int): Boolean {
            val localized = LocalizedContext.of(context)
            return post(
                context,
                localized.resources.getQuantityString(R.plurals.waiting_calls_saved_title, count, count),
                localized.getString(R.string.waiting_calls_saved_text),
                NotificationCompat.PRIORITY_DEFAULT,
            )
        }

        @SuppressLint("MissingPermission")
        private fun post(context: Context, title: String, text: String, priority: Int): Boolean {
            if (!PermissionChecks.hasNotificationPermission(context)) {
                AppLogger.w(TAG, "Folder notice not shown: notification permission is not granted ($title)")
                return false
            }
            return runCatching {
                // The channel this uses is created here as everywhere else that posts on it.
                RecordingNotificationHelper(context).createNotificationChannels()
                val open = PendingIntent.getActivity(
                    context,
                    NotificationDestination.Status.requestCode,
                    Intent(context, MainActivity::class.java).apply {
                        setPackage(context.packageName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(NotificationDestination.EXTRA, NotificationDestination.Status.key)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, RecordingNotificationHelper.CHANNEL_ID_ERROR)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    // A new count updates the notice in place without buzzing again; a dismissed one alerts anew.
                    .setOnlyAlertOnce(true)
                    .setPriority(priority)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .build()
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
                AppLogger.i(TAG, "Folder notice shown: $title")
                true
            }.onFailure { AppLogger.w(TAG, "Could not show the folder notice: ${it.message}") }.getOrDefault(false)
        }
    }
}
