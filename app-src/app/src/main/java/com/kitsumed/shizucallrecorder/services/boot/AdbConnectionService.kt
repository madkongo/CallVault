/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.boot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kitsumed.shizucallrecorder.server.RecorderServerLauncher
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * Short-lived foreground service that runs [AdbShell.ensureConnected] on the IO thread after boot.
 * It re-enables Wireless debugging (if the OEM turned it off) then reconnects ADB so recording
 * works hands-free. Stops itself as soon as the connection attempt completes.
 *
 * Mirrors the notification/foreground pattern of [com.kitsumed.shizucallrecorder.integrations.adb.AdbPairingService].
 */
class AdbConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Startup",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            startForeground(
                NOTIF_ID,
                buildNotification("Preparing call recorder…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            AppLogger.e(TAG, "startForeground failed", it)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification("Preparing call recorder…"))
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Always bring up the persistent recorder daemon on boot so calls record hands-free.
            // ensureServerRunning (transiently) enables Wireless debugging, launches the daemon, waits
            // for its binder, then turns WD back OFF — it internally ensures the ADB connection, so no
            // separate ensureConnected is needed.
            val started = runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }
                .getOrDefault(false)
            AppLogger.i(TAG, "Boot: recorder daemon connected=$started")

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(text)
            .build()

    companion object {
        private const val TAG = "CV:AdbConnectionService"
        private const val CHANNEL_ID = "adb_boot"
        private const val NOTIF_ID = 4713

        fun start(context: Context) {
            val intent = Intent(context, AdbConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
