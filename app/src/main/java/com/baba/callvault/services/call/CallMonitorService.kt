/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger

/**
 * Short-lived, post-reboot foreground service that holds a LIVE telephony call-state listener so the
 * first call(s) after a reboot are detected promptly.
 *
 * **Why this exists.** Call detection normally rides the static [PhoneStateReceiver]
 * `PHONE_STATE` broadcast. On the first call after a reboot, the freshly-booted, overloaded system
 * delivers that broadcast ~9s late — after the call has already ended — so the call is missed
 * (confirmed via the framework's own IMS logs vs. the app's broadcast time). A registered
 * [TelephonyCallback]/[PhoneStateListener] is delivered straight from the telephony service over
 * binder and is NOT stuck behind the post-boot broadcast queue, so it fires in real time.
 *
 * **Bounded by design.** Keeping a registered listener requires the process alive, which on modern
 * Android means a foreground service + notification. In steady state the broadcast is reliable (cold
 * app starts still record fine), so we only need this during the post-boot window: the service runs
 * for [WINDOW_MS] after boot and then stops, so there is no permanent notification.
 *
 * **Authority hand-off.** While this service is listening ([isListening] = true) it is the
 * authoritative call-state source; [PhoneStateReceiver] defers to it (ignores the often-delayed
 * broadcast) to avoid double-handling and stale late-broadcast sessions. The listener forwards STATE
 * only (no number); the number is reconciled exactly as it already is for a number-less start — via
 * the end-of-call CallLog lookup in the recording service.
 */
class CallMonitorService : Service() {

    private val telephonyManager: TelephonyManager? by lazy { getSystemService(TelephonyManager::class.java) }

    /** The currently-registered listener: a [TelephonyCallback] (API 31+) or [PhoneStateListener] (API 30). */
    private var registeredListener: Any? = null

    private val stopHandler = Handler(Looper.getMainLooper())
    private val windowElapsed = object : Runnable {
        override fun run() {
            // Don't pull the only authoritative call-state source out from under an in-progress call;
            // re-arm a short extension and re-check instead.
            if (isCallActive()) {
                AppLogger.i(TAG, "Call-monitor window elapsed but a call is active; deferring stop")
                stopHandler.postDelayed(this, WINDOW_EXTENSION_MS)
                return
            }
            AppLogger.i(TAG, "Post-boot call-monitor window elapsed; stopping live listener")
            stopSelf()
        }
    }

    // Detection (the listener) is ready instantly, but the privileged audio daemon takes ~10-15s to
    // cold-start after a reboot — until then a call is detected but CAN'T be recorded. We poll the
    // daemon's connection state and flip the notification between "starting up" and "ready" so it stays
    // accurate for the whole window (including if the daemon later dies), telling the user when a call
    // will actually be captured.
    private val readinessHandler = Handler(Looper.getMainLooper())
    private var lastReadyState: Boolean? = null
    private val readinessPoll = object : Runnable {
        override fun run() {
            val ready = RecorderConnection.isConnected
            if (ready != lastReadyState) {
                lastReadyState = ready
                updateNotification(ready)
                AppLogger.i(TAG, "Recording readiness changed: ready=$ready")
            }
            readinessHandler.postDelayed(this, READINESS_POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Call monitor", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedForeground = runCatching {
            startForeground(NOTIF_ID, buildNotification(RecorderConnection.isConnected), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { AppLogger.e(TAG, "startForeground failed", it) }.isSuccess
        // A foreground service that can't enter the foreground will be ANR'd/killed on modern Android —
        // bail out cleanly rather than run illegally.
        if (!startedForeground) {
            AppLogger.e(TAG, "Cannot run CallMonitorService without foreground; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        registerListener()

        // Continuously track daemon readiness so the notification stays accurate for the whole window.
        lastReadyState = null
        readinessHandler.removeCallbacks(readinessPoll)
        readinessHandler.post(readinessPoll)

        // (Re)arm the bounded window. START_STICKY may re-deliver with a null intent after a kill;
        // re-arming keeps the listener alive for a fresh window in that case.
        stopHandler.removeCallbacks(windowElapsed)
        stopHandler.postDelayed(windowElapsed, WINDOW_MS)

        return START_STICKY
    }

    private fun updateNotification(ready: Boolean) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ready))
        }.onFailure { AppLogger.w(TAG, "Failed to update monitor notification: ${it.message}") }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopHandler.removeCallbacks(windowElapsed)
        readinessHandler.removeCallbacks(readinessPoll)
        unregisterListener()
        super.onDestroy()
    }

    /** Registers the live call-state listener (idempotent). Requires READ_PHONE_STATE; logs and no-ops if denied. */
    private fun registerListener() {
        if (registeredListener != null) return
        val tm = telephonyManager ?: run {
            AppLogger.w(TAG, "TelephonyManager unavailable; cannot register live call listener")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = CallStateCallback()
                tm.registerTelephonyCallback(mainExecutor, callback)
                registeredListener = callback
            } else {
                val listener = LegacyCallStateListener()
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                registeredListener = listener
            }
        }.onFailure {
            AppLogger.e(TAG, "Failed to register live call listener (READ_PHONE_STATE missing?)", it)
            return
        }
        isListening = true
        AppLogger.i(TAG, "Live call-state listener registered for the post-boot window")
    }

    private fun unregisterListener() {
        val tm = telephonyManager
        val listener = registeredListener
        registeredListener = null
        if (tm != null && listener != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listener is TelephonyCallback) {
                    tm.unregisterTelephonyCallback(listener)
                } else if (listener is PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    tm.listen(listener, PhoneStateListener.LISTEN_NONE)
                }
            }.onFailure { AppLogger.w(TAG, "Failed to unregister live call listener: ${it.message}") }
        }
        // Flip the authority flag only AFTER the listener is actually torn down, so PhoneStateReceiver
        // never resumes the broadcast path while the live listener is still firing (no double-handling).
        isListening = false
    }

    /** Whether the telephony service currently reports an active (non-idle) call. */
    private fun isCallActive(): Boolean =
        runCatching {
            (telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE) != TelephonyManager.CALL_STATE_IDLE
        }.getOrDefault(false)

    /** Maps a [TelephonyManager] call-state int to the broadcast's EXTRA_STATE string and feeds the session manager. */
    private fun forwardState(state: Int) {
        // In dialer mode the InCallService (Telecom) is the authoritative call-state source.
        // Mirrors the identical guard in PhoneStateReceiver to prevent double-feeding.
        if (AppPreferences(this).isDialerModeEnabled()) {
            AppLogger.v(TAG, "Dialer mode active; live listener deferring to Telecom (state forwarding skipped)")
            return
        }
        val stateString = when (state) {
            TelephonyManager.CALL_STATE_RINGING  -> TelephonyManager.EXTRA_STATE_RINGING
            TelephonyManager.CALL_STATE_OFFHOOK  -> TelephonyManager.EXTRA_STATE_OFFHOOK
            TelephonyManager.CALL_STATE_IDLE     -> TelephonyManager.EXTRA_STATE_IDLE
            else -> return
        }
        AppLogger.i(TAG, "Live telephony callback: state=$stateString -> forwarding to CallSessionManager")
        // STATE only (no number); the number is reconciled via the end-of-call CallLog lookup, exactly
        // as in the existing number-less start path.
        CallSessionManager.getInstance(this).handlePhoneState(stateString, null)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class CallStateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = forwardState(state)
    }

    @Suppress("DEPRECATION")
    private inner class LegacyCallStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = forwardState(state)
    }

    private fun buildNotification(ready: Boolean): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (ready) "Ready to record calls" else "Call recorder starting up…")
            .setContentText(
                if (ready) "Listening for calls after restart."
                else "Connecting the recorder — calls won't record until this finishes.",
            )
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val TAG = "CV:CallMonitorService"
        private const val CHANNEL_ID = "call_monitor"
        private const val NOTIF_ID = 4714

        /** How long after boot the live listener stays registered before the broadcast path takes over. */
        private const val WINDOW_MS = 10 * 60 * 1000L

        /** If the window elapses mid-call, defer the stop by this much and re-check. */
        private const val WINDOW_EXTENSION_MS = 60 * 1000L

        /** How often to poll the daemon's connection state to flip the notification to "ready". */
        private const val READINESS_POLL_MS = 1500L

        /**
         * True while the live listener is registered and authoritative. [PhoneStateReceiver] reads this
         * to defer to the listener (ignore the delayed broadcast) during the post-boot window.
         */
        @Volatile
        @JvmStatic
        var isListening: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
