/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
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
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.adb.AdbShell
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
                "ADB boot reconnect",
                NotificationManager.IMPORTANCE_LOW,
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
                buildNotification("Reconnecting ADB…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            AppLogger.e(TAG, "startForeground failed", it)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification("Reconnecting ADB…"))
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching { AdbShell.ensureConnected(applicationContext) }.getOrDefault(false)
            AppLogger.i(TAG, "Boot reconnect result: $ok")

            // Persistent-server mode: launch the privileged recorder daemon now so calls record
            // hands-free after a reboot. ensureServerRunning also applies the WD policy (turns Wireless
            // debugging back off once the daemon's binder is connected, if the user enabled that).
            if (ok && AppPreferences(applicationContext).isPersistentServerEnabled()) {
                val started = runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }
                    .getOrDefault(false)
                AppLogger.i(TAG, "Boot persistent-server launch: connected=$started")
            }

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
        private const val TAG = "SCR:AdbConnectionService"
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
