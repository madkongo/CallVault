/*
 * AdbPairingService — foreground service that drives ADB wireless pairing with an
 * inline-reply notification, so the user only types the 6-digit code.
 *
 * Ported/adapted from RikkaApps/Shizuku (Apache-2.0):
 *   manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt
 * Differences: the actual pairing call uses libadb-android via [SpikeAdbManager]
 * (reusing our persisted key identity) instead of Shizuku's AdbPairingClient, and
 * notification strings/icons are inlined for the spike.
 *
 * Flow:
 *   start  → run mDNS discovery for _adb-tls-pairing._tcp, post "searching" notification
 *   found  → post "service found" notification with a RemoteInput reply action
 *   reply  → user types code in the notification → pair("127.0.0.1", port, code)
 *   result → post success / failure notification
 */

package com.kitsumed.shizucallrecorder.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdbPairingService : Service() {

    private var adbMdns: AdbMdns? = null
    private var started = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ADB pairing (spike)",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification? = when (intent?.action) {
            ACTION_START -> onStart()
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)?.toString().orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (port != -1) onInput(code, port) else onStart()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); null
            }
            else -> return START_NOT_STICKY
        }
        if (notification != null) {
            runCatching {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure {
                Log.e(TAG, "startForeground failed", it)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun onStart(): Notification {
        if (!started) {
            started = true
            adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
                Log.i(TAG, "pairing service port: $port")
                if (port > 0) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, foundNotification(port))
                }
            }.also { it.start() }
        }
        return searchingNotification()
    }

    private fun onInput(code: String, port: Int): Notification {
        CoroutineScope(Dispatchers.IO).launch {
            val paired = runCatching {
                SpikeAdbManager.getInstance(applicationContext).pair("127.0.0.1", port, code)
            }
            stopSearch()
            val notif = if (paired.getOrNull() == true) {
                SpikeLog.append("Paired ✓")
                // Automatically continue: connect → id → forward test.
                val summary = runCatching { SpikeActions.runAutoChain(applicationContext) }
                    .getOrElse { "auto-chain error: ${it.message}" }
                resultNotification("Paired ✓ — checks done", summary)
            } else {
                val msg = paired.exceptionOrNull()?.message ?: "pair() returned false (wrong code?)"
                SpikeLog.append("Pairing failed: $msg")
                resultNotification("Pairing failed", msg)
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return workingNotification()
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
        adbMdns = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notifications ----

    private fun builder() = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)

    private fun searchingNotification(): Notification =
        builder()
            .setContentTitle("Searching for pairing service…")
            .setContentText("Open the phone's 'Pair device with pairing code' dialog.")
            .addAction(stopAction())
            .build()

    private fun foundNotification(port: Int): Notification =
        builder()
            .setContentTitle("Pairing service found")
            .setContentText("Enter the 6-digit pairing code.")
            .addAction(replyAction(port))
            .build()

    private fun workingNotification(): Notification =
        builder().setContentTitle("Pairing…").build()

    private fun resultNotification(title: String, text: String): Notification =
        builder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build()

    private fun stopAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, REQ_STOP, Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, "Stop", pi).build()
    }

    private fun replyAction(port: Int): Notification.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Pairing code").build()
        val pi = PendingIntent.getForegroundService(
            this, REQ_REPLY,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, "Enter pairing code", pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    companion object {
        private const val TAG = "SpikeAdbPairing"
        private const val CHANNEL_ID = "adb_pairing_spike"
        private const val NOTIFICATION_ID = 4711
        private const val REQ_STOP = 2
        private const val REQ_REPLY = 1
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_PORT = "port"
        private const val ACTION_START = "start"
        private const val ACTION_REPLY = "reply"
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            val intent = Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
