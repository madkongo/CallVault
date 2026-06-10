/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kitsumed.shizucallrecorder.integrations.adb.AdbShell
import com.kitsumed.shizucallrecorder.server.RecorderConnection
import com.kitsumed.shizucallrecorder.server.RecorderServerLauncher
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * DEBUG-ONLY throwaway trigger to exercise [RecorderServerLauncher.ensureServerRunning] in isolation
 * (without placing a real call), so the flaky-wireless embedded-ADB launch path can be validated and
 * iterated on. Remove before release.
 *
 * Trigger:
 *   adb shell am broadcast -n com.kfir.callvault/com.kitsumed.shizucallrecorder.debug.LaunchTestReceiver \
 *       -a com.kitsumed.shizucallrecorder.debug.LAUNCH_SERVER
 */
class LaunchTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LAUNCH_SERVER) return
        val appContext = context.applicationContext
        AppLogger.i(TAG, "LAUNCH_SERVER triggered; diagnosing embedded ADB then launching")
        Thread {
            // DIAGNOSTIC: does embedded ADB run a trivial command at all right now?
            val connected = runCatching { AdbShell.ensureConnected(appContext) }.getOrDefault(false)
            AppLogger.i(TAG, "DIAG ensureConnected=$connected")
            runCatching {
                AdbShell.openShell(appContext, "id; echo RC=\$?").use { s ->
                    val out = s.openInputStream().use { String(it.readBytes()) }
                    AppLogger.i(TAG, "DIAG `id` output: ${out.trim().replace("\n", " | ")}")
                }
            }.onFailure { AppLogger.e(TAG, "DIAG `id` FAILED: ${it.javaClass.simpleName}: ${it.message}") }

            val ok = runCatching { RecorderServerLauncher.ensureServerRunning(appContext) }.getOrDefault(false)
            AppLogger.i(TAG, "ensureServerRunning returned=$ok ; RecorderConnection.isConnected=${RecorderConnection.isConnected}")
        }.apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "SCR:RecorderLauncher"
        const val ACTION_LAUNCH_SERVER = "com.kitsumed.shizucallrecorder.debug.LAUNCH_SERVER"
    }
}
