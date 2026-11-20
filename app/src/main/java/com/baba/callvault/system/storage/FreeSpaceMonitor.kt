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
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.utils.AppLogger

/**
 * Reads the phone's free space and acts on [FreeSpacePolicy] (review R2). Read-only and cheap (one stat call): run
 * at app start and at each call start, so no new periodic wake-up exists. Never throws, never blocks a recording.
 */
object FreeSpaceMonitor {
    private const val TAG = "CV:FreeSpace"
    private const val PREFS = "free_space_notice"
    private const val KEY_WARNED = "warned_low"

    /** Not used elsewhere (4712-4728 are). */
    const val NOTIFICATION_ID = 4729

    /** Free bytes on the volume recordings are staged on; -1 when it cannot be read. */
    fun freeBytes(context: Context): Long =
        runCatching { context.applicationContext.filesDir.usableSpace }.getOrDefault(-1L)

    /** Whether the phone is full now (see [FreeSpacePolicy.isStorageFull]). */
    fun isStorageFull(context: Context): Boolean = FreeSpacePolicy.isStorageFull(freeBytes(context))

    private fun threshold(context: Context): Long = runCatching {
        val prefs = AppPreferences(context.applicationContext)
        val codec = ScrcpyAudioCodec.entries.firstOrNull { it.cliKey == prefs.getAudioCodec() } ?: ScrcpyAudioCodec.OPUS
        FreeSpacePolicy.warnThresholdBytes(codec, prefs.getAudioBitRate(), prefs.getRecordStereo())
    }.getOrDefault(FreeSpacePolicy.WARN_FLOOR_BYTES)

    /**
     * Writes the free space to the always-on log (when [logLine]) and warns once if it is low. Call at call start
     * and at app start. A recording is never refused because of what this finds.
     */
    fun check(context: Context, logLine: Boolean) {
        runCatching {
            val free = freeBytes(context)
            val limit = threshold(context)
            if (logLine) CallEndLog.record(context, FreeSpacePolicy.callStartLine(free, limit))
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            when (FreeSpacePolicy.decide(free, limit, prefs.getBoolean(KEY_WARNED, false))) {
                FreeSpacePolicy.Action.WARN -> {
                    val shown = postLowNotice(context, free / (1024 * 1024))
                    CallEndLog.record(context, "free space low: ${free / (1024 * 1024)} MB left, warning " +
                        if (shown) "shown" else "NOT shown (no notification permission, or posting failed)")
                    prefs.edit().putBoolean(KEY_WARNED, true).apply()
                }
                FreeSpacePolicy.Action.REARM -> {
                    prefs.edit().putBoolean(KEY_WARNED, false).apply()
                    runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
                }
                FreeSpacePolicy.Action.NONE -> Unit
            }
        }.onFailure { AppLogger.w(TAG, "Free-space check failed: ${it.message}") }
    }

    /** Tells the user the phone is full: [textRes] on the errors channel. Returns whether it was handed to the system. */
    @SuppressLint("MissingPermission")
    fun postStorageNotice(context: Context, titleRes: Int, text: String): Boolean {
        if (!PermissionChecks.hasNotificationPermission(context)) return false
        return runCatching {
            val localized = LocalizedContext.of(context)
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
                .setContentTitle(localized.getString(titleRes))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrElse {
            AppLogger.w(TAG, "Storage notice not shown: ${it.message}")
            false
        }
    }

    private fun postLowNotice(context: Context, freeMb: Long): Boolean {
        val localized = LocalizedContext.of(context)
        return postStorageNotice(context, R.string.storage_low_title, localized.getString(R.string.storage_low_text, freeMb))
    }
}
