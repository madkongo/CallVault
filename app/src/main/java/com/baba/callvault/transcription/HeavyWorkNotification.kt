/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.baba.callvault.R

/**
 * Keeps a long on-device job alive by making it visible.
 *
 * Transcription and summarisation both run for minutes and hold hundreds of megabytes — a whisper model
 * and, for a summary, a multi-gigabyte language model. As plain `CoroutineWorker`s they ran at
 * **cached-process priority**, which is the first thing Android reclaims under memory pressure. The
 * failure that produces is perfectly circular: the job is killed *because* it is using memory, halfway
 * through, having produced nothing, after the user has waited.
 *
 * A foreground service is the only way to tell Android this work is worth keeping. The cost is that the
 * user must be told too, which is the correct trade rather than a nuisance: their phone is about to get
 * warm and use noticeable battery, and a silent explanation-free slowdown is worse than a notification.
 *
 * The channel is **IMPORTANCE_MIN** — present in the shade, no sound, no vibration, no heads-up.
 */
object HeavyWorkNotification {

    private const val CHANNEL_ID = "heavy_work"

    /** Distinct from the recording notifications, which must never be crowded out by this. */
    private const val NOTIFICATION_ID = 42

    /**
     * A [ForegroundInfo] describing the running job, for `setForeground`.
     *
     * @param titleRes what the user is told is happening. Naming the actual task matters: "CallVault is
     *   busy" invites the reader to guess, and the honest answer — their own recording is being turned
     *   into text — is also the reassuring one.
     */
    fun forWork(context: Context, titleRes: Int): ForegroundInfo {
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(R.string.heavy_work_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        // The type must match what the manifest declares for WorkManager's service, or Android 14+
        // refuses to start it at all.
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /** Its own id, so a Drive copy and a transcription running together each keep their notification. */
    private const val DRIVE_COPY_NOTIFICATION_ID = 43

    /**
     * The scheduled Drive copy, with a counter, a bar and "Stop for now" (chosen 2026-11-03, option C).
     *
     * Same silent channel as transcription. Being foreground is what lets a big backlog run past Android's
     * ~10-minute limit for background jobs (Honor BVL-N49, 2026-11-03).
     */
    fun forDriveCopy(context: Context, copied: Int, total: Int, stop: PendingIntent): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.drive_copy_title))
            .setContentText(context.getString(R.string.drive_copy_progress, copied, total))
            .setProgress(total, copied, false)
            .addAction(0, context.getString(R.string.drive_copy_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        return ForegroundInfo(DRIVE_COPY_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /** Idempotent: creating an existing channel updates nothing the user has since changed. */
    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.heavy_work_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
