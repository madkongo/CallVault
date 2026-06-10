/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.server

import android.content.Context
import com.kitsumed.shizucallrecorder.data.AppPreferences
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
    private const val DRAIN_BUDGET_MS = 1200L

    /** Poll interval while waiting for the daemon to deliver its binder to [RecorderConnection]. */
    private const val POLL_INTERVAL_MS = 150L

    /**
     * How many launch attempts to make. The wireless embedded-ADB link is flaky ("Stream closed"),
     * and a single openShell can silently fail to deliver the command, so we retry with a fresh
     * connection between attempts.
     */
    private const val MAX_LAUNCH_ATTEMPTS = 3

    /**
     * Ensures the privileged recorder daemon is running and its binder is available in
     * [RecorderConnection]. Call OFF the main thread (does ADB network I/O and polls/sleeps).
     *
     * Fast path: if [RecorderConnection.isConnected] is already true, returns true immediately.
     * Otherwise it makes up to [MAX_LAUNCH_ATTEMPTS] launch attempts: ensure the embedded ADB
     * connection, detached-launch [RecorderServer], then poll [RecorderConnection.isConnected] for a
     * slice of [timeoutMs]. The detached `&` launch makes the launching shell exit immediately, so a
     * "Stream closed" while draining is EXPECTED and is NOT treated as failure — the real success
     * signal is the binder arriving. If an attempt does not connect, the connection may be stale, so
     * we [AdbShell.forceReconnect] before retrying.
     *
     * @param context   App context; its `applicationInfo.sourceDir` (the installed APK) is the CLASSPATH.
     * @param timeoutMs Total budget to wait for the daemon's binder across all attempts.
     * @return true if the daemon's binder is available in [RecorderConnection] (already or after launch).
     */
    fun ensureServerRunning(context: Context, timeoutMs: Long = 12_000): Boolean {
        if (RecorderConnection.isConnected) {
            AppLogger.d(TAG, "Recorder daemon already connected; reusing existing binder")
            applyWdPolicy(context)
            return true
        }

        val apk = context.applicationInfo.sourceDir
        val perAttemptMs = (timeoutMs / MAX_LAUNCH_ATTEMPTS).coerceAtLeast(2000L)

        repeat(MAX_LAUNCH_ATTEMPTS) { attempt ->
            val n = attempt + 1
            // (Re)establish ADB. On a retry, force a fresh connection — a stale half-dead connection
            // still reports isConnected but its openStream throws "Stream closed". This also re-enables
            // Wireless debugging if the WD policy had turned it off (needed to relaunch the daemon).
            val connected = if (attempt == 0) AdbShell.ensureConnected(context)
            else AdbShell.forceReconnect(context)
            if (!connected) {
                AppLogger.w(TAG, "Attempt $n/$MAX_LAUNCH_ATTEMPTS: ADB not connected; retrying")
                return@repeat
            }

            launchOnce(context, apk, n)

            if (pollConnected(perAttemptMs)) {
                AppLogger.i(TAG, "Recorder daemon connected on attempt $n; binder available")
                applyWdPolicy(context)
                return true
            }
            AppLogger.w(TAG, "Attempt $n/$MAX_LAUNCH_ATTEMPTS: binder not delivered within ${perAttemptMs}ms")
        }

        val ok = RecorderConnection.isConnected
        AppLogger.w(TAG, "ensureServerRunning gave up after $MAX_LAUNCH_ATTEMPTS attempts; connected=$ok")
        return ok
    }

    /**
     * Applies the WD policy now that the daemon binder is connected: if the user chose
     * "turn Wireless debugging off when idle", drop WD (the daemon needs no ADB at record time — it is
     * commanded over binder). Re-enabled transiently by [ensureServerRunning] when a relaunch is needed.
     * No-op when the policy is off or WD is already off.
     */
    private fun applyWdPolicy(context: Context) {
        if (!AppPreferences(context).isWdDisableWhenIdle()) return
        if (AdbShell.disableWirelessDebugging(context)) {
            AppLogger.i(TAG, "WD policy: disabled Wireless debugging (daemon connected; commands flow over binder)")
        } else {
            AppLogger.w(TAG, "WD policy: could not disable Wireless debugging (missing WRITE_SECURE_SETTINGS?)")
        }
    }

    /**
     * Fires the detached launch command once over the embedded ADB shell and drains briefly so it is
     * delivered. A "Stream closed" here is expected (the `&`-backgrounded launcher shell exits at
     * once and the daemon's own stdio is /dev/null), so failures are logged at debug, not error.
     */
    private fun launchOnce(context: Context, apk: String, attempt: Int) {
        val command = String.format(PRIMARY_CMD_FORMAT, apk)
        AppLogger.i(TAG, "Attempt $attempt: launching recorder daemon. apk=$apk")
        runCatching {
            AdbShell.openShell(context, command).use { shell ->
                val deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS
                shell.openInputStream().use { input ->
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < deadline) {
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read > 0) AppLogger.d(TAG, "[launch] ${String(buf, 0, read)}")
                    }
                }
            }
        }.onFailure { AppLogger.d(TAG, "Attempt $attempt drain ended (expected for detached &): ${it.message}") }
    }

    /** Polls [RecorderConnection.isConnected] up to [waitMs]. Returns true as soon as connected. */
    private fun pollConnected(waitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (RecorderConnection.isConnected) return true
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }
        return RecorderConnection.isConnected
    }
}
