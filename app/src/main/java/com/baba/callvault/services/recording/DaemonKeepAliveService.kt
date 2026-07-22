/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.baba.callvault.R
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.RecorderServerLauncher
import com.baba.callvault.utils.AppLogger

/**
 * Persistent foreground service that keeps CallVault's privileged recorder daemon WARM — our
 * equivalent of what Shizuku's manager app does for its server: a durable foreground presence that
 * anchors the app process and actively re-warms the daemon the moment it dies, so a call is captured
 * instantly (over binder, no Wi-Fi needed) instead of paying a ~10–24s cold-start that outlasts short
 * calls.
 *
 * **Why a foreground service.** OnePlus/ColorOS reaps our detached shell-uid daemon after a few minutes
 * of idle. Holding a foreground service (a) keeps our own process important, giving the daemon a best-
 * effort priority anchor, and (b) lets a watchdog notice the daemon died and relaunch it BEFORE the
 * next call, not during it. Recording itself flows over the daemon's binder, so once warm, calls record
 * with Wi-Fi off — this service is the thing that keeps it warm.
 *
 * **Battery-safe.** The watchdog is a cheap 60s binder-liveness check. A relaunch is only attempted
 * when the daemon is down AND Wi-Fi is available (Wireless debugging needs Wi-Fi to relaunch) and is
 * throttled — so off-Wi-Fi idle costs nothing (the daemon simply can't be relaunched there; that gap is
 * what the opt-in loopback mode covers).
 */
class DaemonKeepAliveService : Service() {

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastReady: Boolean? = null

    @Volatile private var rewarming = false
    private var lastRewarmAtMs = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            val ready = RecorderConnection.isConnected
            if (ready != lastReady) {
                lastReady = ready
                updateNotification(ready)
                AppLogger.d(TAG, "keep-alive: daemon ready=$ready")
            }
            if (!ready) maybeRewarm()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_readiness_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedForeground = runCatching {
            startForeground(NOTIF_ID, buildNotification(RecorderConnection.isConnected), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { AppLogger.e(TAG, "keep-alive startForeground failed", it) }.isSuccess
        if (!startedForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Kick the watchdog now (first tick warms the daemon immediately if it's cold).
        lastReady = null
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.post(watchdog)
        // START_STICKY: if the OS kills us under pressure, restart so the daemon anchor comes back.
        return START_STICKY
    }

    /**
     * Relaunch the daemon if it's down — but only when it can actually succeed (Wi-Fi up, since
     * Wireless debugging needs it) and not more than once per [REWARM_THROTTLE_MS]. When the daemon is
     * already connected the [watchdog] never calls this, so an active recording (daemon connected) is
     * implicitly skipped and never churned.
     */
    private fun maybeRewarm() {
        if (rewarming) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRewarmAtMs < REWARM_THROTTLE_MS) return
        if (!isWifiConnected()) return // off-Wi-Fi we can't relaunch (WD needs Wi-Fi) — save battery
        lastRewarmAtMs = now
        rewarming = true
        Thread {
            AppLogger.i(TAG, "keep-alive: daemon down — relaunching over Wi-Fi")
            runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }
                .onFailure { AppLogger.w(TAG, "keep-alive relaunch failed: ${it.message}") }
            rewarming = false
        }.apply { isDaemon = true; name = "cv-keepalive-rewarm" }.start()
    }

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    private fun updateNotification(ready: Boolean) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ready))
        }
    }

    private fun buildNotification(ready: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(
                getString(if (ready) R.string.notif_readiness_ready_title else R.string.notif_readiness_starting_title),
            )
            .setContentText(
                getString(if (ready) R.string.notif_readiness_ready_text else R.string.notif_readiness_starting_text),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CV:DaemonKeepAlive"
        private const val CHANNEL_ID = "recorder_keepalive"
        private const val NOTIF_ID = 4720

        /** How often the watchdog checks the daemon is alive. Cheap (a binder ping). */
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        /** Minimum gap between daemon relaunch attempts, so a persistently-down daemon isn't hammered. */
        private const val REWARM_THROTTLE_MS = 90_000L

        /** Start (or no-op if already running) the persistent keep-alive anchor. Safe to call repeatedly. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DaemonKeepAliveService::class.java))
            }.onFailure { AppLogger.w(TAG, "Failed to start keep-alive service: ${it.message}") }
        }

        /** Stop the keep-alive anchor (e.g. if the user disables persistence). */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DaemonKeepAliveService::class.java)) }
        }
    }
}
