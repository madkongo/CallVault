/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger
import rikka.shizuku.Shizuku

/**
 * Notices, while CallVault runs, that Shizuku has stopped — and helps it be started again.
 *
 * Measured on the OP9 on 2026-09-14 (S3 in docs/dev-notes/2026-09-14-debugging-switches-model.md): with
 * CallVault in Shizuku mode, turning USB debugging off stopped adbd, Shizuku's server with it, and CallVault's
 * recorder. The home screen said "Shizuku is not ready" — and nothing else did. The "cannot record" warning
 * only posted from the boot path, and nothing listened for Shizuku's binder dying, so a user who did not
 * open the app would learn it from a missed call.
 *
 * The second half: after USB debugging goes off, Wireless debugging still reads on while adbd is stopped, so
 * Shizuku's own "Start via Wireless debugging" has nothing to connect to. Switching it off and on starts adbd
 * again (✅ OP9). That cycle ends with the switch as the user left it, so it is allowed in Shizuku mode; turning
 * the switch on from off is not.
 */
object ShizukuLifecycleWatcher {

    private const val TAG = "CV:ShizukuWatch"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        runCatching {
            Shizuku.addBinderDeadListener { onDead(app) }
            // Sticky: if Shizuku is already up when this installs, clear any warning left from before.
            Shizuku.addBinderReceivedListenerSticky { onReceived(app) }
        }.onFailure { AppLogger.w(TAG, "Could not watch Shizuku: ${it.message}") }
    }

    private fun inShizukuMode(context: Context) =
        runCatching { AppPreferences(context).getPrivilegedMode().needsShizuku }.getOrDefault(false)

    private fun onDead(context: Context) {
        if (!inShizukuMode(context)) return
        AppLogger.w(TAG, "Shizuku stopped; calls will not be recorded until it is started again")
        SilentFailureNotifier.warnRecorderUnavailable(context)
        Thread {
            runCatching { AdbShell.reviveAdbdIfStopped(context, "Shizuku stopped", mayEnable = false) }
                .onFailure { AppLogger.w(TAG, "Could not check adbd after Shizuku stopped: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-shizuku-dead" }.start()
    }

    private fun onReceived(context: Context) {
        if (!inShizukuMode(context)) return
        AppLogger.i(TAG, "Shizuku is running")
        SilentFailureNotifier.clearRecorderUnavailable(context)
        Thread {
            runCatching { RecorderBackend.ensureRunning(context) }
                .onFailure { AppLogger.w(TAG, "Could not start the recorder after Shizuku returned: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-shizuku-back" }.start()
    }
}
