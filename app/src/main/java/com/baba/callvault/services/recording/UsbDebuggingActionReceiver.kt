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
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.UsbDebuggingRestorer
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.utils.AppLogger

/**
 * The "Turn USB debugging on" button on the readiness notification.
 *
 * Offered when USB debugging is off and recording is paused or down because of it. The tap is the user's own
 * request, so it also switches on Honor/Huawei's "Allow ADB debugging in 'Charge only' mode" when that is off —
 * without it their phone switches USB debugging off again the next time it is unplugged.
 */
class UsbDebuggingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            val on = try {
                UsbDebuggingRestorer.turnOn(app, why = "user tap", alsoChargingAdb = true)
            } finally {
                // Finish before the relaunch, which can take the whole launch budget (see WirelessDebuggingActionReceiver).
                pending.finish()
            }
            AppLogger.i(TAG, "User asked to turn USB debugging back on: adbd ${if (on) "running" else "not running"}")
            runCatching { RecorderBackend.ensureRunning(app) }
                .onFailure { AppLogger.w(TAG, "Could not start the recorder after turning USB debugging on: ${it.message}") }
            runCatching { DaemonKeepAliveService.start(app) }
        }.apply { isDaemon = true; name = "cv-usb-action" }.start()
    }

    companion object {
        private const val TAG = "CV:UsbAction"
        private const val ACTION = "com.baba.callvault.action.TURN_USB_DEBUGGING_ON"

        /** Whether the notification for [notice] carries the button: USB debugging proven off, and the grant to write it. */
        fun offers(context: Context, notice: ReadinessNotice): Boolean =
            (notice == ReadinessNotice.NEEDS_WIFI || notice == ReadinessNotice.READY_OFFLINE_PAUSED ||
                notice == ReadinessNotice.STUCK) &&
                AdbShell.usbDebuggingState(context).isOff &&
                AdbShell.hasWriteSecureSettings(context)

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, UsbDebuggingActionReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
