/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.server

import android.content.Context
import com.kitsumed.shizucallrecorder.integrations.adb.AdbShell
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * CallVault Plan 5, Task 4 — PRODUCTION launcher for the persistent privileged recorder daemon.
 *
 * Detached-launches [RecorderServer] (shell uid 2000, no Activity) over the app's embedded ADB shell
 * using the proven `setsid sh -c 'CLASSPATH=<apk> exec app_process / <fqcn> <args>'` technique so the
 * daemon survives Wireless debugging being turned OFF. The daemon then pushes its [IRecorderService]
 * binder to the exported [RecorderBinderProvider], which populates [RecorderConnection]; this launcher
 * polls that holder until the binder arrives (or the timeout elapses).
 *
 * Mirrors the launch+drain style of the proven spike
 * [com.kitsumed.shizucallrecorder.persistserver.PersistDaemonLauncher.launch]: open the shell, drain
 * stdout briefly so the command is delivered, close, then poll the connection holder. The detached
 * daemon's own stdio is `/dev/null`, so the drain only captures the launching shell's exit.
 */
object RecorderServerLauncher {

    private const val TAG = "SCR:RecorderLauncher"

    /** Fully-qualified class name app_process invokes (its `static void main(String[])`). */
    private const val DAEMON_FQCN = "com.kitsumed.shizucallrecorder.server.RecorderServer"

    /**
     * PRIMARY detached launch format — identical detach technique to the proven spike: setsid into a
     * fresh session/process-group, stdio -> /dev/null so a closing adb pipe cannot SIGHUP/EOF us, and
     * backgrounded. `%1$s` is the app APK path, used twice: as the CLASSPATH for app_process AND as the
     * daemon's first positional arg (`apkPath`, used by [RecorderServer] to self-extract scrcpy).
     */
    private const val PRIMARY_CMD_FORMAT =
        "setsid sh -c 'CLASSPATH=%1\$s exec app_process / $DAEMON_FQCN %1\$s' >/dev/null 2>&1 </dev/null &"

    /** How long to drain stdout so the shell command is actually delivered before we close. */
    private const val DRAIN_BUDGET_MS = 1500L

    /** Poll interval while waiting for the daemon to deliver its binder to [RecorderConnection]. */
    private const val POLL_INTERVAL_MS = 150L

    /**
     * Ensures the privileged recorder daemon is running and its binder is available in
     * [RecorderConnection]. Call OFF the main thread (does ADB network I/O and polls/sleeps).
     *
     * Fast path: if [RecorderConnection.isConnected] is already true, returns true immediately. Otherwise
     * it ensures the embedded ADB connection, detached-launches [RecorderServer] with the app APK as both
     * CLASSPATH and `apkPath` arg, drains the launching shell briefly, then polls
     * [RecorderConnection.isConnected] every [POLL_INTERVAL_MS] until [timeoutMs] elapses.
     *
     * @param context   App context; its `applicationInfo.sourceDir` (the installed APK) is the CLASSPATH.
     * @param timeoutMs Max time to wait for the daemon's binder to be delivered after launch.
     * @return true if the daemon's binder is available in [RecorderConnection] (already or after launch).
     */
    fun ensureServerRunning(context: Context, timeoutMs: Long = 8000): Boolean {
        if (RecorderConnection.isConnected) {
            AppLogger.d(TAG, "Recorder daemon already connected; reusing existing binder")
            return true
        }

        if (!AdbShell.ensureConnected(context)) {
            AppLogger.e(TAG, "ADB not connected; cannot launch recorder daemon")
            return false
        }

        val apk = context.applicationInfo.sourceDir
        val command = String.format(PRIMARY_CMD_FORMAT, apk)
        AppLogger.i(TAG, "Launching recorder daemon. apk=$apk")
        AppLogger.i(TAG, "Recorder launch command: $command")

        runCatching {
            AdbShell.openShell(context, command).use { shell ->
                // Drain briefly so the command is delivered, mirroring PersistDaemonLauncher. The
                // detached daemon's own stdio is /dev/null, so this only captures the launching shell's exit.
                val deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS
                shell.openInputStream().use { input ->
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < deadline) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) AppLogger.d(TAG, "[launch] ${String(buf, 0, n)}")
                    }
                }
            }
            AppLogger.i(TAG, "Recorder daemon launch command delivered; shell closed")
        }.onFailure { AppLogger.e(TAG, "Recorder daemon launch failed: ${it.message}", it) }

        // Poll the connection holder until the daemon delivers its binder to RecorderBinderProvider.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (RecorderConnection.isConnected) {
                AppLogger.i(TAG, "Recorder daemon connected; binder available in RecorderConnection")
                return true
            }
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }

        val connected = RecorderConnection.isConnected
        AppLogger.w(TAG, "ensureServerRunning finished connected=$connected (timeout=${timeoutMs}ms)")
        return connected
    }
}
