/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger

/**
 * Shizuku mode: notices the phone joining a Wi-Fi network without any running component (review N15).
 *
 * A `ConnectivityManager` request held by a [PendingIntent] is delivered as a broadcast even when CallVault's process
 * is not running, so no foreground service and no new persistent notification are needed (the keep-alive runs in
 * Shizuku mode only for app calls, and stays that way). Delivered on each Wi-Fi "available", which a roam between
 * access points of one network does not produce. Registered by [WifiJoinWatch.sync]; it does not survive a reboot,
 * and the app start re-registers it.
 */
class WifiJoinApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiJoinWatch.ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // Let Android finish its own "Wi-Fi changed" handling first, and a cold-started app connect to
                // the recorder Shizuku is still running (the app start binds it).
                Thread.sleep(WifiJoinWatch.JOIN_SETTLE_MS)
                awaitRecorder(WifiJoinWatch.RECORDER_WAIT_MS)
                ShizukuWifiApproval.forApp(app).approveIfWanted()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Wi-Fi join approval failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; name = "cv-wifi-approve-shizuku" }.start()
    }

    private fun awaitRecorder(timeoutMs: Long) {
        var waited = 0L
        while (!recorderAlive() && waited < timeoutMs) {
            Thread.sleep(WifiJoinWatch.RECORDER_POLL_MS)
            waited += WifiJoinWatch.RECORDER_POLL_MS
        }
    }

    private fun recorderAlive() = RecorderConnection.service?.asBinder()?.isBinderAlive == true

    private companion object {
        const val TAG = "CV:WifiApproveShizuku"
    }
}

/** Keeps the Wi-Fi join watch registered exactly while Shizuku mode's approval is wanted. */
object WifiJoinWatch {

    const val ACTION = "com.baba.callvault.WIFI_JOINED_APPROVE"

    /** After a join, before approving: Android writes its own "Wi-Fi changed" switch-off first (built-in mode's 4 s). */
    const val JOIN_SETTLE_MS = 4_000L

    /** How long a join waits for the recorder connection; well inside a background broadcast's time. */
    const val RECORDER_WAIT_MS = 15_000L
    const val RECORDER_POLL_MS = 500L

    private const val TAG = "CV:WifiApproveShizuku"
    private const val REQUEST_CODE = 4_731

    fun shouldWatch(mode: PrivilegedMode, settingOn: Boolean): Boolean = mode.needsShizuku && settingOn

    /** Registers or removes the watch for the current mode and setting. Idempotent; safe on any thread. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val prefs = AppPreferences(app)
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: run {
            AppLogger.w(TAG, "No ConnectivityManager; cannot watch Wi-Fi joins")
            return
        }
        val intent = pendingIntent(app)
        if (shouldWatch(prefs.getPrivilegedMode(), prefs.isWifiNetworkAutoApproveEnabled())) {
            // An equal PendingIntent replaces the earlier request, so this never stacks.
            runCatching {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    intent,
                )
            }.onSuccess { AppLogger.i(TAG, "Watching Wi-Fi joins for the Shizuku-mode approval") }
                .onFailure { AppLogger.w(TAG, "Could not watch Wi-Fi joins: ${it.javaClass.simpleName}: ${it.message}") }
        } else {
            runCatching { cm.unregisterNetworkCallback(intent) }
                .onFailure { AppLogger.d(TAG, "No Wi-Fi join watch to remove: ${it.message}") }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        // Mutable: Android adds the network to the intent. Explicit, to our own unexported receiver.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, WifiJoinApprovalReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }
}
