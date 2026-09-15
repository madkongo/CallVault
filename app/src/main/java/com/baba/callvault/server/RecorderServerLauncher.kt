/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.Context
import android.os.SystemClock
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.utils.AppLogger

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
 * [com.baba.callvault.persistserver.PersistDaemonLauncher.launch]: open the shell, drain
 * stdout briefly so the command is delivered, close, then poll the connection holder. The detached
 * daemon's own stdio is `/dev/null`, so the drain only captures the launching shell's exit.
 */
object RecorderServerLauncher {

    private const val TAG = "CV:RecorderLauncher"

    /** Fully-qualified class name app_process invokes (its `static void main(String[])`). */
    private const val DAEMON_FQCN = "com.baba.callvault.server.RecorderServer"

    /**
     * Seconds the launching shell stays alive (a trailing `sleep`) AFTER backgrounding the daemon.
     * CRITICAL for the embedded-ADB transport: unlike laptop `adb shell`, libadb closes the shell
     * stream almost immediately (~30ms), and adbd then kills the just-spawned child before `app_process`
     * has loaded the APK + reparented to init — so the daemon never comes up. Keeping the parent shell
     * alive past app_process startup lets the daemon fully detach before the stream closes. (Proven gap:
     * the identical command works from laptop adb, which holds the stream open longer.)
     * NOTE: must be declared BEFORE [PRIMARY_CMD_FORMAT] — Kotlin const interpolation needs it initialised.
     */
    private const val LAUNCH_KEEPALIVE_SEC = 3

    /**
     * PRIMARY detached launch format — identical detach technique to the proven spike: setsid into a
     * fresh session/process-group, stdio -> /dev/null so a closing adb pipe cannot SIGHUP/EOF us, and
     * backgrounded, then a trailing `sleep` keeps the launching shell alive while the daemon starts.
     * `%1$s` is the app APK path, used twice: as the CLASSPATH for app_process AND as the daemon's first
     * positional arg (`apkPath`, used by [RecorderServer] to self-extract scrcpy).
     */
    private const val PRIMARY_CMD_FORMAT =
        "setsid sh -c 'CLASSPATH=%1\$s exec app_process / $DAEMON_FQCN %1\$s' >/dev/null 2>&1 </dev/null & sleep $LAUNCH_KEEPALIVE_SEC"

    /**
     * How long to block-drain the launching shell. Must exceed [LAUNCH_KEEPALIVE_SEC] so we hold the
     * stream open for the whole keep-alive window (the read returns EOF when the shell's sleep ends).
     */
    private const val DRAIN_BUDGET_MS = 4000L

    /**
     * Kills any already-running [RecorderServer] daemon before launching a fresh one. The daemon is a
     * detached shell-uid `app_process` reparented to init, so it SURVIVES app uninstall/update — without
     * this, stale OLD-code daemons accumulate across reinstalls/updates and orphan until reboot.
     *
     * Uses `pgrep -f` (a single fast kernel scan) — the previous `for d in /proc/[0-9]*; do grep …`
     * loop forked a `grep` per process and measured **~25 s** on this device, dwarfing the whole rest of
     * a relaunch (the dominant chunk of the post-death "starting up" window a call races). pgrep is ~70 ms.
     *
     * Self-exclusion: the FQCN is written `Recorder[S]erver` so this command's OWN cmdline (which contains
     * the literal `Recorder[S]erver`) does NOT match the regex, while a real daemon's cmdline
     * (`…RecorderServer …`) does. Run only on the launch path (binder not connected), and only AFTER
     * [AdbShell.waitForShellReady] confirms the shell answers, so pgrep can't hang mid-restart.
     */
    private const val KILL_STALE_CMD =
        "p=\$(pgrep -f 'com.baba.callvault.server.Recorder[S]erver' 2>/dev/null); [ -n \"\$p\" ] && kill \$p 2>/dev/null; true"

    /** Drain cap for [killStaleDaemons]; the command isn't backgrounded, so it usually EOFs sooner. */
    private const val KILL_DRAIN_MS = 1500L

    /**
     * Below this device uptime, a reboot has just cleared every process, so no stale/orphaned daemon
     * can exist — [killStaleDaemons] would only burn a shell round-trip on the latency-critical
     * post-boot cold start. Stale daemons only accumulate from reinstall-over WITHOUT a reboot, which
     * happens at much higher uptimes.
     */
    private const val RECENT_BOOT_GRACE_MS = 90_000L

    /** Poll interval while waiting for the daemon to deliver its binder to [RecorderConnection]. */
    private const val POLL_INTERVAL_MS = 150L

    /**
     * How many launch attempts to make. The wireless embedded-ADB link is flaky ("Stream closed"),
     * and a single openShell can silently fail to deliver the command, so we retry with a fresh
     * connection between attempts.
     */
    private const val MAX_LAUNCH_ATTEMPTS = 3

    /** Per-attempt budget to wait for the loopback shell to actually answer (adbd finishing a restart). */
    private const val SHELL_READY_TIMEOUT_MS = 6_000L

    /**
     * Hard bound on the opportunistic USB-default refresh in [applyWdPolicy]. Generous for a local
     * `dumpsys` (normally milliseconds) but small enough that a wedged ADB stream cannot delay the start
     * of a recording — an unbounded read there loses the entire call.
     */
    private const val USB_REFRESH_TIMEOUT_MS = 1_500L

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
     *
     * **@Synchronized**: serialise launch attempts. STANDBY and START_RECORDING (and boot) can call this
     * near-simultaneously; without serialisation the concurrent runs call disconnect()/reconnect on each
     * other mid-connect and spawn duplicate daemons — observed to thrash the embedded ADB connection into
     * a "Stream closed" state. One launch at a time; the second caller sees the connected binder and returns.
     */
    // 24s total / 3 attempts = 8s per attempt. The old 12s (=4s/attempt) was shorter than the daemon's
    // cold-start, so attempt 1 ALWAYS timed out and we burned ~8s before even retrying — the biggest
    // avoidable chunk of the post-reap "starting up" window a call races. 8s comfortably covers a cold boot.
    fun ensureServerRunning(context: Context, timeoutMs: Long = 24_000): Boolean =
        // Serialize with the update installer (and other daemon launches) on the shared ADB lock, so
        // a launch's reconnect/WD-toggle never tears down an in-flight update install stream.
        synchronized(AdbShell.heavyOperationLock) {
            // Holds the Wireless-debugging lease across the whole launch. Without it, a settings screen
            // finishing its USB probe could switch WD off midway through — and switching it off drops
            // the embedded ADB connection this launch is running on.
            AdbShell.asAdbUser(context, "the daemon launch") { ensureServerRunningLocked(context, timeoutMs) }
        }

    private fun ensureServerRunningLocked(context: Context, timeoutMs: Long): Boolean {
        if (RecorderConnection.isConnected) {
            AppLogger.d(TAG, "Recorder daemon already connected; reusing existing binder")
            applyWdPolicy(context)
            return true
        }

        val apk = context.applicationInfo.sourceDir
        val perAttemptMs = (timeoutMs / MAX_LAUNCH_ATTEMPTS).coerceAtLeast(2000L)

        repeat(MAX_LAUNCH_ATTEMPTS) { attempt ->
            val n = attempt + 1
            // A prior attempt's daemon may have delivered its binder late; if so, don't kill+relaunch it.
            if (RecorderConnection.isConnected) {
                AppLogger.i(TAG, "Recorder daemon connected before attempt $n; reusing binder")
                applyWdPolicy(context)
                return true
            }
            // (Re)establish ADB. On a retry, force a fresh connection — a stale half-dead connection
            // still reports isConnected but its openStream throws "Stream closed". This also re-enables
            // Wireless debugging if the WD policy had turned it off (needed to relaunch the daemon).
            var connected = if (attempt == 0) AdbShell.ensureConnected(context)
            else AdbShell.forceReconnect(context)
            // Offline mode: if we couldn't connect, the loopback listener is DOWN (e.g. tcpip cleared on
            // reboot). Re-ARM it (a deliberate, transient WD bootstrap) rather than run on persistent
            // Wireless Debugging — WD's adbd churn is what kills the daemon. Held under heavyOperationLock
            // (this method), so armLoopbackIfNeeded's lock is re-entrant. No-op/fast when already armed.
            if (!connected && AppPreferences(context).isOfflineRecordingEnabled()) {
                AppLogger.i(TAG, "Attempt $n: offline mode but no connection — re-arming loopback")
                if (AdbShell.armLoopbackIfNeeded(context)) connected = AdbShell.ensureConnected(context)
            }
            if (!connected) {
                AppLogger.w(TAG, "Attempt $n/$MAX_LAUNCH_ATTEMPTS: ADB not connected; retrying")
                return@repeat
            }

            // A TCP connect to loopback can succeed while adbd is still mid-restart (OnePlus restarts adbd
            // on screen transitions), so the launch command would land on a dead shell and we'd wait out
            // the whole poll for a binder that never arrives. Confirm the shell actually round-trips FIRST
            // — the daemon boots instantly once the command lands, so this turns a ~40s stall into seconds.
            if (!AdbShell.waitForShellReady(context, SHELL_READY_TIMEOUT_MS)) {
                AppLogger.w(TAG, "Attempt $n/$MAX_LAUNCH_ATTEMPTS: loopback shell not ready (adbd restarting); retrying")
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
     * Now that the daemon's binder is connected, turn Wireless debugging OFF — the daemon is commanded
     * over binder and needs no ADB until it must be relaunched (then [ensureServerRunning] re-enables WD
     * transiently). This is unconditional: "WD only when needed" is CallVault's core behaviour, not a
     * user toggle. No-op if WD is already off or WRITE_SECURE_SETTINGS is missing.
     */
    private fun applyWdPolicy(context: Context) {
        // We still hold the ADB shell here (before WD is turned off) — opportunistically refresh the
        // USB-default cache that drives the "locking the screen may stop recording" warning.
        //
        // MUST be bounded: this runs on the critical path of STARTING A RECORDING. The dumpsys read is
        // normally instant (or fails fast with "Stream closed"), but over a half-dead ADB connection the
        // stream read BLOCKS INDEFINITELY — which silently hangs `startPipeline` and loses the whole call
        // (observed repeatedly on-device: the log stops right here and the output file stays 0 bytes).
        // The refresh is opportunistic, so a timeout simply means the cached value stays stale.
        refreshUsbDefaultBounded(context)

        // `adbd` stops when its LAST transport goes away, and the daemon is a child of an adbd shell —
        // so switching Wireless debugging off while it is the only transport kills the daemon that was
        // just launched. Measured on a Galaxy S24 FE: death 50-90 ms after each disable, then relaunch,
        // then re-enable, six times over two minutes, never reaching "ready to record". Only switch it
        // off when something else is holding adbd up.
        // Through the shared release, so the ownership rule applies here too. This used to call
        // disableWirelessDebugging directly, which is how a switch the user had turned on still went off
        // after the ownership check was added everywhere else: the launcher runs on every daemon start,
        // so it was the one path that reached the setting first (#30).
        AdbShell.releaseWirelessDebugging(context, "the daemon launch")
    }

    /**
     * Runs the opportunistic USB-default refresh with a hard time bound so it can never stall a recording.
     *
     * The read is done on a throwaway daemon thread and joined for at most [USB_REFRESH_TIMEOUT_MS]; if
     * the underlying ADB stream is wedged the thread is simply abandoned (it unblocks whenever the stream
     * finally errors) and we carry on with a stale cached value. Correctness of the recording never
     * depends on this value — it only drives an advisory UI warning.
     */
    private fun refreshUsbDefaultBounded(context: Context) {
        val worker = Thread { runCatching { UsbDefaultConfig.readIfConnected(context) } }
            .apply { isDaemon = true; name = "cv-usbdefault-refresh" }
        worker.start()
        runCatching { worker.join(USB_REFRESH_TIMEOUT_MS) }
        if (worker.isAlive) {
            // Interrupt rather than only abandon: its read waits interruptibly, and an abandoned one never ended —
            // two were still parked on the OP12 hours later.
            worker.interrupt()
            AppLogger.w(TAG, "USB-default refresh still blocked after ${USB_REFRESH_TIMEOUT_MS}ms; continuing (stale value)")
        }
    }

    /**
     * Fires the detached launch command once over the embedded ADB shell and returns as soon as the
     * daemon's binder has arrived — WITHOUT waiting out the whole keep-alive window.
     *
     * The launching shell must stay open for [LAUNCH_KEEPALIVE_SEC] so the daemon fully detaches
     * before the stream closes; we satisfy that by draining the shell on a background thread (which
     * also delivers the command). But once the binder is in [RecorderConnection] the daemon has
     * ALREADY detached, so there is no reason to keep blocking the caller. Previously this method
     * block-read the shell for the full ~3-4s keep-alive on every launch, which meant a binder that
     * arrived early (the common case) was not noticed until the drain ended — adding ~3s of dead
     * latency that could push readiness PAST the end of a short call (the "call ended before the
     * daemon was ready" abort).
     *
     * A "Stream closed" on the drain thread is expected (the `&`-backgrounded launcher shell exits at
     * once and the daemon's own stdio is /dev/null), so failures are logged at debug, not error.
     */
    private fun launchOnce(context: Context, apk: String, attempt: Int) {
        // Clear any stale/orphaned daemon (e.g. old code surviving a reinstall) before launching fresh,
        // so at most one daemon — matching the installed code — is ever running. Skipped shortly after
        // boot: a reboot already cleared every process, so the scan can only cost time on the
        // latency-critical first post-boot launch.
        if (SystemClock.elapsedRealtime() > RECENT_BOOT_GRACE_MS) {
            killStaleDaemons(context)
        } else {
            AppLogger.d(TAG, "Recent boot (uptime < ${RECENT_BOOT_GRACE_MS}ms); skipping killStaleDaemons — reboot already cleared any stale daemon")
        }
        val command = String.format(PRIMARY_CMD_FORMAT, apk)
        AppLogger.i(TAG, "Attempt $attempt: launching recorder daemon. apk=$apk")

        // Background thread: send the command and hold the shell's stream open for the keep-alive
        // window so the daemon detaches. Owns the shell's whole lifetime; outlives this method if the
        // binder arrives first (harmless — it just finishes draining and closes).
        Thread {
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
        }.apply { isDaemon = true; name = "recorder-launch-drain-$attempt" }.start()

        // Return the moment the binder lands; otherwise wait out the keep-alive window (the launch
        // failed to come up and the caller's retry will reconnect + relaunch).
        val deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS
        while (System.currentTimeMillis() < deadline) {
            if (RecorderConnection.isConnected) {
                AppLogger.d(TAG, "Attempt $attempt: binder arrived during launch; not waiting out keep-alive")
                return
            }
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }
    }

    /**
     * Best-effort: kill any already-running [RecorderServer] daemon over the embedded ADB shell so a
     * fresh launch never has to coexist with an orphaned/stale one. See [KILL_STALE_CMD] for the
     * self-exclusion detail. The command is not backgrounded, so the shell EOFs when the scan finishes.
     */
    private fun killStaleDaemons(context: Context) {
        runCatching {
            AdbShell.openShell(context, KILL_STALE_CMD).use { shell ->
                val deadline = System.currentTimeMillis() + KILL_DRAIN_MS
                shell.openInputStream().use { input ->
                    val buf = ByteArray(128)
                    while (System.currentTimeMillis() < deadline) {
                        if (input.read(buf) < 0) break
                    }
                }
            }
        }.onFailure { AppLogger.d(TAG, "killStaleDaemons ended: ${it.message}") }
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
