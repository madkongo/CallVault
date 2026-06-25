/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.services.call.CallMonitorService
import com.baba.callvault.utils.AppLogger

/**
 * Posts a transient "Call recorder starting up… → Ready to record calls" notification while the
 * privileged recorder daemon is cold-starting at app launch.
 *
 * The daemon is a separate `app_process` that must be (re)launched over ADB whenever it isn't already
 * running — after a reboot, an app update/reinstall, or whenever the OS killed the app process. Until
 * it connects (~5–15s, ADB/adbd-bound) a call is detected but CAN'T be recorded, so this tells the
 * user when it's actually safe to place a call.
 *
 * Triggered from the existing startup warm-up ([com.baba.callvault.CallVaultApplication]); it only
 * appears when the daemon is genuinely cold (so opening the app with a warm daemon shows nothing) and
 * auto-dismisses shortly after it's ready. The post-reboot path already shows readiness via
 * [CallMonitorService]'s foreground notification, so this defers to it ([CallMonitorService.isListening])
 * to avoid a duplicate.
 */
object RecorderReadinessNotifier {

    private const val TAG = "CV:RecorderReadiness"
    private const val CHANNEL_ID = "recorder_readiness"
    private const val NOTIFICATION_ID = 4715

    /** How long the "ready" notification lingers before auto-dismissing. */
    private const val READY_LINGER_MS = 6_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Daemon is cold and warming up — show "starting up". No-op if the reboot listener owns the UI. */
    fun showStarting(context: Context) {
        if (CallMonitorService.isListening) return
        post(context, ready = false)
    }

    /** Daemon connected — flip to "ready" and auto-dismiss shortly after. */
    fun showReadyThenDismiss(context: Context) {
        if (CallMonitorService.isListening) {
            dismiss(context)
            return
        }
        post(context, ready = true)
        mainHandler.postDelayed({ dismiss(context) }, READY_LINGER_MS)
    }

    fun dismiss(context: Context) {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun post(context: Context, ready: Boolean) {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (ready) "Ready to record calls" else "Call recorder starting up…")
            .setContentText(
                if (ready) "The recorder is connected."
                else "Connecting the recorder — calls won't record until this finishes.",
            )
            .setOngoing(!ready)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        // notify() silently no-ops without POST_NOTIFICATIONS (Android 13+); acceptable here.
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onFailure { AppLogger.w(TAG, "Failed to post readiness notification: ${it.message}") }
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Call recorder readiness",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
