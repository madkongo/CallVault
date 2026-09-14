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
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.utils.AppLogger

/**
 * The "Turn Wireless debugging on" button on the paused-recording notification.
 *
 * CallVault leaves a Wireless-debugging switch the user turned off alone by default (2026-09-14). This is the
 * user asking for it back in one tap, so it runs as a user request — the only way past that respect.
 */
class WirelessDebuggingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val on = AdbShell.asUserRequest { AdbShell.enableWirelessDebugging(app) }
                AppLogger.i(TAG, "User asked to turn Wireless debugging back on: ${if (on) "on" else "refused (see the log above)"}")
                if (on) runCatching { RecorderBackend.ensureRunning(app) }
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; name = "cv-wd-action" }.start()
    }

    companion object {
        private const val TAG = "CV:WdAction"
        private const val ACTION = "com.baba.callvault.action.TURN_WIRELESS_DEBUGGING_ON"

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WirelessDebuggingActionReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
