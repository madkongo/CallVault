/*
 * AdbPairingService — foreground service that drives ADB wireless pairing with an
 * inline-reply notification, so the user only types the 6-digit code.
 *
 * Ported/adapted from RikkaApps/Shizuku (Apache-2.0):
 *   manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt
 * Differences: the actual pairing call uses libadb-android via [AdbConnectionManager]
 * (reusing our persisted key identity) instead of Shizuku's AdbPairingClient.
 *
 * Flow:
 *   start  → run mDNS discovery for _adb-tls-pairing._tcp, post "searching" notification
 *   found  → post "service found" notification with a RemoteInput reply action
 *   reply  → user types code in the notification → pair("127.0.0.1", port, code)
 *   result → post success / failure notification
 */

package com.baba.callvault.integrations.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.baba.callvault.MainActivity
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.utils.AppLogger

class AdbPairingService : Service() {

    private var adbMdns: AdbMdns? = null
    private var started = false

    // Guards against running the pairing handshake more than once (e.g. a re-delivered ACTION_REPLY
    // or a double notification reply) — which would post a second, confusing "Paired ✓" notification.
    @Volatile
    private var pairingHandled = false

    // ---- "Wait for Wireless debugging" pre-state ----
    // When ACTION_START arrives while Wireless debugging (WD) is OFF, we don't start mDNS discovery
    // yet. Instead we post a "waiting" foreground notification and watch the adb_wifi_enabled global
    // setting; the moment the user toggles WD on we transition into the normal discovery flow. This
    // lets the user tap "Open Wireless debugging" once and never return to CallVault to authorize.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wdObserver: ContentObserver? = null
    private val wdTimeoutRunnable = Runnable { onWaitTimedOut() }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_pairing_channel),
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
                stopWaitingForWd(); stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); null
            }
            else -> return START_NOT_STICKY
        }
        if (notification != null) {
            runCatching {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.onFailure {
                AppLogger.e(TAG, "startForeground failed", it)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        }
        // NOT_STICKY: pairing is a one-shot, user-driven flow. We must never let the system
        // re-deliver ACTION_REPLY (which would re-run the handshake and post a duplicate "Paired ✓").
        return START_NOT_STICKY
    }

    /**
     * ACTION_START entry. If Wireless debugging is already ON we start mDNS discovery immediately
     * (the original behaviour). If it is OFF we enter the WAITING pre-state instead — see
     * [beginWaitingForWd] — and only start discovery once the user toggles WD on.
     */
    private fun onStart(): Notification {
        AppLogger.i(TAG, "ACTION_START (started=$started) at +${sinceCreateMs()}ms; ${wdReadForLog()}")
        if (started) return searchingNotification()
        return if (AdbShell.isWirelessDebuggingEnabled(applicationContext)) {
            AppLogger.i(TAG, "Wireless debugging reads ON — starting pairing discovery now")
            beginDiscovery()
            searchingNotification()
        } else {
            beginWaitingForWd()
            waitingForWdNotification()
        }
    }

    /** When this service instance was created, so every pairing line can say how long into the attempt it is. */
    private val createdAtMs = SystemClock.elapsedRealtime()
    private fun sinceCreateMs(): Long = SystemClock.elapsedRealtime() - createdAtMs

    /**
     * What this phone says about Wireless debugging, raw, for the log.
     *
     * Android 17 QPR3 betas hide `adb_wifi_enabled` from apps (it reads as missing or 0 whatever the truth), and
     * the bare boolean [AdbShell.isWirelessDebuggingEnabled] cannot tell "off" from "hidden". So the raw read
     * (-1 = the key is absent), the two corroborating facts and the platform are all printed. Never throws.
     */
    private fun wdReadForLog(): String {
        val raw = runCatching {
            Settings.Global.getInt(applicationContext.contentResolver, "adb_wifi_enabled", -1).toString()
        }.getOrElse { "threw ${it.javaClass.simpleName}" }
        val adbd = runCatching { AdbShell.adbdState().name }.getOrDefault("?")
        val grant = runCatching { AdbShell.hasWriteSecureSettings(applicationContext) }.getOrDefault("?")
        return "adb_wifi_enabled=$raw (-1 = key absent), init.svc.adbd=$adbd, WRITE_SECURE_SETTINGS=$grant, " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MODEL}"
    }

    /** Whether this phone's Wireless debugging read is untrustworthy, so every mDNS callback goes to the journal. */
    private fun reportsNsd(): Boolean {
        val raw = runCatching {
            Settings.Global.getInt(applicationContext.contentResolver, "adb_wifi_enabled", -1)
        }.getOrDefault(-1)
        return PairingWaitPolicy.settingMayBeHidden(Build.VERSION.SDK_INT, raw)
    }

    /** Begins mDNS discovery for the pairing service. Idempotent via [started]. */
    private fun beginDiscovery() {
        if (started) return
        started = true
        val begun = SystemClock.elapsedRealtime()
        AppLogger.i(TAG, "Pairing discovery started at +${sinceCreateMs()}ms")
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, label = if (reportsNsd()) "discovery" else null) { port ->
            AppLogger.i(TAG, "pairing service port: $port, ${SystemClock.elapsedRealtime() - begun}ms after discovery began")
            if (port > 0) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, foundNotification(port))
            }
        }.also { it.start() }
    }

    /**
     * Registers a [ContentObserver] on the adb_wifi_enabled global setting and a safety timeout.
     * When WD flips on we cancel the timeout, unregister the observer, start discovery, and swap the
     * foreground notification to the "searching" one — all without the user returning to CallVault.
     * Idempotent: a second ACTION_START while already waiting is a no-op.
     */
    private fun beginWaitingForWd() {
        if (wdObserver != null) return
        AppLogger.i(TAG, "WD reads off (or hidden) — arming pairing; waiting for Wireless debugging to be turned on " +
            "(setting may be hidden: ${reportsNsd()}, notification names the path: ${PairingWaitPolicy.usesPathWording(Build.VERSION.SDK_INT)})")
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                AppLogger.i(TAG, "adb_wifi_enabled changed at +${sinceCreateMs()}ms; ${wdReadForLog()}")
                if (AdbShell.isWirelessDebuggingEnabled(applicationContext)) {
                    AppLogger.i(TAG, "Wireless debugging turned on — starting pairing discovery")
                    stopWaitingForWd()
                    beginDiscovery()
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, searchingNotification())
                    }.onFailure { AppLogger.e(TAG, "notify(searching) after WD-on failed", it) }
                }
            }
        }
        wdObserver = observer
        runCatching {
            val uri = Settings.Global.getUriFor("adb_wifi_enabled")
            contentResolver.registerContentObserver(uri, false, observer)
        }.onFailure {
            AppLogger.e(TAG, "registerContentObserver(adb_wifi_enabled) failed", it)
            wdObserver = null
        }
        mainHandler.postDelayed(wdTimeoutRunnable, WD_WAIT_TIMEOUT_MS)
        // Probe: on a phone that hides adb_wifi_enabled (Android 17 QPR3 betas) the read above stays "off" while
        // Wireless debugging is really on, and the wait would run out with nothing found. A pairing service
        // that is actually advertised proves it is on, so listen for one while waiting; if one shows up, carry
        // on into normal discovery. The probe changes no setting and posts nothing by itself.
        val probeStart = SystemClock.elapsedRealtime()
        probe = AdbMdns(this, AdbMdns.TLS_PAIRING, label = if (reportsNsd()) "probe" else null) { port ->
            if (port > 0) mainHandler.post {
                if (wdObserver == null || started) return@post
                AppLogger.w(
                    TAG,
                    "A pairing service is advertised on port $port ${SystemClock.elapsedRealtime() - probeStart}ms " +
                        "into the wait although Wireless debugging reads off — the setting is hidden from apps. " +
                        "Going on with pairing. ${wdReadForLog()}"
                )
                stopWaitingForWd()
                beginDiscovery()
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, searchingNotification())
                }
            }
        }.also { it.start() }
        // Heartbeats: what the setting reads while the user is in the system pairing dialog, with timings.
        for (delayMs in WAIT_HEARTBEATS_MS) mainHandler.postDelayed({
            if (wdObserver != null) AppLogger.i(TAG, "Still waiting at +${sinceCreateMs()}ms; ${wdReadForLog()}")
        }, delayMs)
    }

    /** The log-only-until-proven mDNS listener used while waiting. See [beginWaitingForWd]. */
    private var probe: AdbMdns? = null

    /** Tears down the WD-waiting observer + timeout. Safe to call when not waiting. */
    private fun stopWaitingForWd() {
        // Everything posted to this service's own handler is waiting-state work (timeout, heartbeats, probe hand-off).
        mainHandler.removeCallbacksAndMessages(null)
        probe?.stop()
        probe = null
        wdObserver?.let { obs ->
            runCatching { contentResolver.unregisterContentObserver(obs) }
                .onFailure { AppLogger.d(TAG, "unregisterContentObserver ignored: ${it.message}") }
        }
        wdObserver = null
    }

    /** Safety net: WD never turned on within the timeout — tear down and stop the service. */
    private fun onWaitTimedOut() {
        AppLogger.w(TAG, "Wireless debugging not enabled within timeout (+${sinceCreateMs()}ms) — stopping pairing service; ${wdReadForLog()}")
        stopWaitingForWd()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onInput(code: String, port: Int): Notification {
        // Ignore a second/redelivered reply so we never pair twice or post a duplicate result.
        if (pairingHandled) return workingNotification()
        pairingHandled = true
        CoroutineScope(Dispatchers.IO).launch {
            // The code itself is never logged, only its length.
            AppLogger.i(TAG, "Pairing code submitted (${code.length} chars) to 127.0.0.1:$port at +${sinceCreateMs()}ms")
            val pairStart = SystemClock.elapsedRealtime()
            val paired = runCatching {
                AdbConnectionManager.getInstance(applicationContext).pair("127.0.0.1", port, code)
            }
            AppLogger.i(
                TAG,
                "pair() returned ${paired.getOrNull() ?: "an exception (${paired.exceptionOrNull()?.javaClass?.simpleName})"} " +
                    "after ${SystemClock.elapsedRealtime() - pairStart}ms"
            )
            stopWaitingForWd()
            stopSearch()
            val nm = getSystemService(NotificationManager::class.java)
            if (paired.getOrNull() == true) {
                AppLogger.i(TAG, "ADB pairing succeeded on port $port")
                // Persist the paired flag so OnboardingStatus.adbConnected flips to true on the next
                // refresh. pair() itself does not set this — only AdbShell.ensureConnected does, and
                // we want the onboarding card to read "Granted" even before the first shell connect.
                AppPreferences(applicationContext).setAdbPaired(true)
                PairingLoss.recordConnected(applicationContext)
                // Pairing itself is the slow-but-bounded part the user is waiting on (~a few seconds).
                // The privileged daemon is NOT needed until the first recording, and launching it is the
                // slow ~10s dance — so we DON'T block the "done" signal on it. Post the tappable success
                // notification NOW so the user can return immediately, then pre-warm the daemon in the
                // background while this (still-foreground) service stays alive.
                // (A background Service can't reliably foreground an Activity on Android 12+ — the
                // contentIntent on this notification gives a reliable one-tap return instead.)
                nm.notify(NOTIFICATION_ID, resultNotification(
                    getString(R.string.notif_pairing_success_title),
                    getString(R.string.notif_pairing_success_text),
                    tapToOpen = true,
                ))
                runCatching { RecorderBackend.ensureRunning(applicationContext) }
                    .onFailure { AppLogger.e(TAG, "ensureServerRunning after pairing failed", it) }
            } else {
                val msg = paired.exceptionOrNull()?.message ?: "pair() returned false (wrong code?)"
                AppLogger.e(TAG, "ADB pairing failed on port $port: $msg", paired.exceptionOrNull())
                nm.notify(NOTIFICATION_ID, resultNotification(getString(R.string.notif_pairing_failed_title), msg))
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return workingNotification()
    }

    /** A tap on the (success) notification opens CallVault so onboarding can continue. */
    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NotificationDestination.EXTRA, NotificationDestination.Pairing.key)
        // REQ_OPEN is retired in favour of the destination's own request code: every Intent into
        // MainActivity looks the same to PendingIntent, so the codes have to be allocated in one
        // place or two notifications silently share one. See NotificationDestination.requestCode.
        return PendingIntent.getActivity(
            this, NotificationDestination.Pairing.requestCode, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
        adbMdns = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWaitingForWd()
        stopSearch()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notifications ----

    private fun builder() = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)

    private fun searchingNotification(): Notification =
        builder()
            .setContentTitle(getString(R.string.notif_pairing_searching_title))
            .setContentText(getString(R.string.notif_pairing_searching_text))
            .addAction(stopAction())
            .build()

    private fun waitingForWdNotification(): Notification =
        builder()
            .setContentTitle(getString(R.string.notif_pairing_waiting_wd_title))
            .setContentText(
                getString(
                    if (PairingWaitPolicy.usesPathWording(Build.VERSION.SDK_INT)) R.string.notif_pairing_waiting_wd_text_path
                    else R.string.notif_pairing_waiting_wd_text
                )
            )
            .setOngoing(true)
            .addAction(stopAction())
            .build()

    private fun foundNotification(port: Int): Notification =
        builder()
            .setContentTitle(getString(R.string.notif_pairing_found_title))
            .setContentText(getString(R.string.notif_pairing_found_text))
            .addAction(replyAction(port))
            .build()

    private fun workingNotification(): Notification =
        builder()
            .setContentTitle(getString(R.string.notif_pairing_working_title))
            .setContentText(getString(R.string.notif_pairing_working_text))
            .setOngoing(true)
            .setProgress(0, 0, true) // indeterminate — reassures the user something is happening
            .build()

    private fun resultNotification(title: String, text: String, tapToOpen: Boolean = false): Notification =
        builder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .apply {
                if (tapToOpen) {
                    setContentIntent(openAppIntent())
                    setAutoCancel(true)
                }
            }
            .build()

    private fun stopAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, REQ_STOP, Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, getString(R.string.notif_pairing_action_stop), pi).build()
    }

    private fun replyAction(port: Int): Notification.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(getString(R.string.notif_pairing_remote_input_label)).build()
        val pi = PendingIntent.getForegroundService(
            this, REQ_REPLY,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_PORT, port),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(R.string.notif_pairing_action_enter_code), pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    companion object {
        // "CV:" like every other tag, and so named in SetupJournalPolicy. The old bare tag was not on that list, so
        // no pairing line ever reached the always-on setup journal (BabApps item 92).
        const val TAG = "CV:AdbPairing"

        /** Re-reads of the Wireless debugging setting while waiting, so a stuck wait shows what the phone said. */
        private val WAIT_HEARTBEATS_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L, 240_000L)
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 4712
        private const val REQ_STOP = 2
        private const val REQ_REPLY = 1
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_PORT = "port"
        private const val ACTION_START = "start"
        private const val ACTION_REPLY = "reply"
        private const val ACTION_STOP = "stop"

        /** Safety cap on the "waiting for Wireless debugging" pre-state (5 minutes). */
        private const val WD_WAIT_TIMEOUT_MS = 5 * 60 * 1000L

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
