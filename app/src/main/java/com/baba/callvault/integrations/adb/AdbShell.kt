/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import io.github.muntashirakon.adb.AdbStream

/** Thin facade over the embedded ADB connection for the recording pipeline. */
object AdbShell {
    private const val TAG = "CV:AdbShell"

    /**
     * Serializes ADB operations that hold the connection for a while or tear it down/rebuild it —
     * the recorder-daemon launch (which toggles Wireless debugging and reconnects) and the update
     * installer (which streams the whole APK over one exec: stream). Run concurrently they corrupt
     * each other: the daemon launcher's reconnect closes the installer's in-flight stream ("Stream
     * closed mid-send"). Both take this lock so one waits for the other instead of colliding.
     */
    val heavyOperationLock = Any()
    private const val CONNECT_SETTLE_MS = 2500L
    /** Reduced from 25 s so the recording path fails fast instead of hanging while falsely appearing to record. */
    private const val MDNS_TIMEOUT_MS = 12_000L
    /**
     * Small settle after toggling Wireless debugging on, before we begin mDNS discovery. Kept short
     * (was 4 s) because [AdbMdns.discoverPort] is event-driven and already blocks until adbd actually
     * advertises — so a long fixed pre-sleep was mostly dead time on the post-boot cold-start path.
     */
    private const val WD_START_WAIT_MS = 750L
    /** Time to let adbd restart into tcp mode after opening the `tcpip:` service, before reconnecting. */
    private const val TCPIP_RESTART_WAIT_MS = 1500L
    /** Settle after dropping the (now-dead) pre-restart connection, before the loopback reconnect. */
    private const val POST_DISCONNECT_WAIT_MS = 500L
    /** Hard cap on the tcpip: arm open (adbd restarts mid-open, so the call can stall — don't wait forever). */
    private const val ARM_FIRE_CAP_MS = 3000L
    /** Max time to wait for an armed-but-restarting loopback listener to reappear before re-arming via WD. */
    private const val LOOPBACK_SELFHEAL_MS = 12_000L
    /** Poll interval while waiting for the loopback listener to self-heal after an adbd restart. */
    private const val LOOPBACK_RETRY_INTERVAL_MS = 1500L
    /** Sentinel echoed by [waitForShellReady] to confirm adbd actually answers a shell command. */
    private const val SHELL_PROBE_TOKEN = "cv_shell_ok"
    /** Poll interval between shell-readiness probes while adbd is coming back after a restart. */
    private const val SHELL_PROBE_INTERVAL_MS = 400L
    /** Hard cap per shell probe so a half-open (mid-restart) adbd connection can never hang the caller. */
    private const val SHELL_PROBE_CAP_MS = 1500L

    /**
     * Ensures the ADB connection is up (mDNS-discover the connect port, connect, settle).
     * Call off main thread. On success, persists the "ADB paired" flag so the onboarding gate
     * is not shown again on subsequent launches (a live connection only exists per-process).
     *
     * If Wireless debugging is off (e.g. after an OEM reboot) and the app holds
     * WRITE_SECURE_SETTINGS, it re-enables Wireless debugging before starting mDNS discovery.
     *
     * **@Synchronized**: when a call wakes the app, the launch auto-connect (CallVaultApplication) and
     * the recording path both call this at once. Without serialization they raced — one connected
     * while the other checked `isConnected` a moment too early, tried its own connect, lost, and
     * reported "not connected". Serializing makes the second caller wait, then see the live
     * connection and return true.
     *
     * @return true if already connected or newly connected successfully; false on failure.
     */
    @Synchronized
    fun ensureConnected(context: Context): Boolean {
        val mgr = AdbConnectionManager.getInstance(context)
        if (mgr.isConnected) {
            AppPreferences(context).setAdbPaired(true)
            grantSecureSettingsIfNeeded(context)
            return true
        }
        // OFFLINE MODE = LOOPBACK ONLY. We deliberately do NOT enable Wireless Debugging on this hot path:
        // writing adb_wifi_enabled restarts adbd, and when classic-tcpip (loopback) is the daemon's only
        // lifeline that restart KILLS the daemon. On-device heartbeat proof: the WD-enable churn was the
        // PRIMARY cause of every daemon death — with WD kept off and loopback armed the daemon persists
        // across screen-off + deep doze (~30 min, Athena-limited). If loopback is DOWN (e.g. tcpip cleared
        // on reboot) we do NOT fall back to persistent WD here; the launcher re-arms loopback via
        // [armLoopbackIfNeeded] (a deliberate, transient one-time WD bootstrap), which is not this churn.
        if (AppPreferences(context).isOfflineRecordingEnabled()) {
            var ok = connectLoopback(context)
            // Loopback can be MOMENTARILY unavailable while adbd restarts (a USB plug/unplug transition,
            // or a WD toggle) — but the tcpip listener SELF-HEALS because `service.adb.tcp.port` persists
            // across adbd restarts, so it reappears within a few seconds. If the port is still armed, WAIT
            // for it rather than re-arm via WD (which is what caused the transient WD "blips" on unplug).
            // Only when the port is genuinely gone (e.g. tcpip cleared on reboot) do we let the caller
            // re-arm — distinguished by isLoopbackArmed() so a post-reboot connect isn't delayed here.
            if (!ok && isLoopbackArmed(context)) {
                var waited = 0L
                while (!ok && waited < LOOPBACK_SELFHEAL_MS) {
                    Thread.sleep(LOOPBACK_RETRY_INTERVAL_MS)
                    waited += LOOPBACK_RETRY_INTERVAL_MS
                    ok = connectLoopback(context)
                }
                if (ok) AppLogger.i(TAG, "Loopback self-healed after ${waited}ms (adbd restart) — no WD needed")
            }
            if (ok) {
                Thread.sleep(CONNECT_SETTLE_MS)
                AppPreferences(context).setAdbPaired(true)
                grantSecureSettingsIfNeeded(context)
            }
            return ok
        }
        return connectViaWirelessDebugging(context)
    }

    /**
     * True if adbd's classic-tcpip listener is armed on OUR loopback port — i.e. `service.adb.tcp.port`
     * equals [AppPreferences.getLoopbackAdbPort]. The property PERSISTS across adbd restarts (only a
     * reboot clears it), so "armed but connect refused" means adbd is mid-restart and the port will come
     * back — worth waiting for instead of re-arming via Wireless Debugging.
     */
    private fun isLoopbackArmed(context: Context): Boolean =
        getSystemProperty("service.adb.tcp.port") == AppPreferences(context).getLoopbackAdbPort().toString()

    /** Reads a system property via the hidden `SystemProperties.get` (reflection; public SDK-safe). */
    private fun getSystemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String ?: ""
    }.getOrDefault("")

    /**
     * Wireless-Debugging bootstrap: enable WD if the OEM turned it off, mDNS-discover the connect port,
     * connect, settle. This is the ONLY path that writes `adb_wifi_enabled=1`. Used by the non-offline
     * recording path and by [armLoopbackIfNeeded] for the one-time base connection it needs to arm the
     * loopback listener (arming inherently needs a transport once).
     *
     * @Synchronized (instance monitor) — where both are held it is always acquired AFTER
     * [heavyOperationLock], matching every other lock site (heavy → instance), so no inversion.
     */
    @Synchronized
    private fun connectViaWirelessDebugging(context: Context): Boolean {
        val mgr = AdbConnectionManager.getInstance(context)
        if (mgr.isConnected) return true
        // Re-enable Wireless debugging if the OEM turned it off on reboot (needs WRITE_SECURE_SETTINGS).
        if (!isWirelessDebuggingEnabled(context) && enableWirelessDebugging(context)) {
            AppLogger.i(TAG, "Re-enabled Wireless debugging; waiting for adbd to advertise…")
            Thread.sleep(WD_START_WAIT_MS)
        }
        val port = AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, MDNS_TIMEOUT_MS) ?: return false
        // connect() THROWS on an unpaired/unauthorised identity (AdbPairingRequiredException) or a flaky
        // TLS handshake (SSLProtocolException: CERTIFICATE_UNKNOWN). Callers branch on the boolean —
        // propagating crashed the app at onboarding's "Setup ADB" step — so swallow to false.
        val ok = runCatching { mgr.connect("127.0.0.1", port) }.getOrElse { e ->
            AppLogger.w(TAG, "ADB connect failed (pairing required or transport error): ${e.message}")
            false
        }
        if (ok) {
            Thread.sleep(CONNECT_SETTLE_MS)
            AppPreferences(context).setAdbPaired(true)
            grantSecureSettingsIfNeeded(context)
        }
        return ok
    }

    /**
     * Forces a fresh ADB connection: drops the current one (so a half-dead/stale connection that
     * still reports `isConnected` is discarded) then re-runs [ensureConnected] to rediscover the
     * mDNS port and reconnect. Used by the recorder-server launcher to recover from intermittent
     * "Stream closed" failures on the flaky wireless link.
     *
     * Call off the main thread. @Synchronized for the same reason as [ensureConnected] (serialise
     * concurrent connect attempts). Re-entrant with [ensureConnected] (same thread).
     *
     * @return true if a live connection is up after the reconnect attempt.
     */
    @Synchronized
    fun forceReconnect(context: Context): Boolean {
        runCatching { AdbConnectionManager.getInstance(context).disconnect() }
            .onFailure { AppLogger.d(TAG, "forceReconnect disconnect ignored: ${it.message}") }
        return ensureConnected(context)
    }

    /**
     * Opens an ADB shell stream for [command]. The full `shell:` prefix is added automatically.
     *
     * @param context App context.
     * @param command Shell command string (without the "shell:" prefix).
     * @return An [AdbStream] connected to the shell process.
     */
    fun openShell(context: Context, command: String): AdbStream =
        AdbConnectionManager.getInstance(context).openStream("shell:$command")

    /**
     * Polls a trivial shell round-trip until adbd actually answers, or [timeoutMs] elapses.
     *
     * On OnePlus, adbd RESTARTS on screen on/off transitions (even cable-out, WD off) — a TCP connect to
     * the loopback port can succeed while adbd is still mid-init, so firing the daemon-launch command then
     * lands on a dead shell and we wait out a full launch timeout for a binder that never comes. Verifying
     * a cheap `echo` round-trips first means we launch ONLY into a live shell — the daemon then boots
     * instantly. Cheap and bounded: each probe is one short shell exec; the daemon relaunch calls this
     * right before [RecorderServerLauncher.launchOnce].
     *
     * @return true once a shell command round-trips; false if none did within [timeoutMs].
     */
    fun waitForShellReady(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var probes = 0
        while (System.currentTimeMillis() < deadline) {
            probes++
            if (probeShellOnce(context)) {
                if (probes > 1) AppLogger.i(TAG, "Loopback shell became ready after $probes probes")
                return true
            }
            Thread.sleep(SHELL_PROBE_INTERVAL_MS)
        }
        AppLogger.w(TAG, "Loopback shell not ready within ${timeoutMs}ms ($probes probes) — adbd still restarting?")
        return false
    }

    /**
     * One shell round-trip, HARD-CAPPED at [SHELL_PROBE_CAP_MS]. Runs the exec on a throwaway daemon
     * thread and joins with a timeout: if adbd is mid-restart the connection can half-open so the read
     * never sees EOF — we then close the stream (unblocking the read) and report "not ready" instead of
     * hanging the caller. This is the fix for the stall the un-bounded version caused on unplug.
     */
    private fun probeShellOnce(context: Context): Boolean {
        val streamRef = java.util.concurrent.atomic.AtomicReference<AdbStream?>()
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            runCatching {
                val s = openShell(context, "echo $SHELL_PROBE_TOKEN")
                streamRef.set(s)
                s.use { if (it.openInputStream().bufferedReader().use { r -> r.readText() }.contains(SHELL_PROBE_TOKEN)) ok.set(true) }
            }
        }.apply { isDaemon = true; name = "cv-shell-probe" }
        t.start()
        t.join(SHELL_PROBE_CAP_MS)
        if (t.isAlive) {
            runCatching { streamRef.get()?.close() } // unblock the hung read so the abandoned thread dies
            return false
        }
        return ok.get()
    }

    /**
     * Opens an ADB `exec:` stream for [command] — a RAW, PTY-less bidirectional stream. Use this
     * (not [openShell]) whenever binary data is streamed to a command's stdin: `shell:` allocates a
     * pseudo-terminal that performs newline translation and can truncate on certain bytes, corrupting
     * or prematurely closing a binary stream (e.g. an APK piped to `pm install -S`). `exec:` is the
     * same channel desktop `adb install` uses for exactly this reason.
     *
     * @param context App context.
     * @param command Command string (without the "exec:" prefix).
     * @return An [AdbStream] connected to the command with a raw binary I/O channel.
     */
    fun openExec(context: Context, command: String): AdbStream =
        AdbConnectionManager.getInstance(context).openStream("exec:$command")

    /**
     * Opens an ADB localabstract socket by [name]. The full `localabstract:` prefix is added automatically.
     *
     * @param context App context.
     * @param name    The abstract socket name (without the "localabstract:" prefix).
     * @return An [AdbStream] connected to the abstract socket.
     */
    fun openLocalAbstract(context: Context, name: String): AdbStream =
        AdbConnectionManager.getInstance(context).openStream("localabstract:$name")

    // ---- Off-WiFi recording: persistent classic-tcpip loopback listener (opt-in) ----

    /**
     * Attempts the persistent classic-tcpip loopback connection (`127.0.0.1:<port>`).
     *
     * Unlike Wireless Debugging this needs NO WiFi (loopback is always up) — but only works once the
     * port has been armed since the last reboot (see [armLoopbackIfNeeded]). Returns false FAST
     * (connection refused) when unarmed, so callers fall back to Wireless Debugging. libadb-android
     * reacts to the daemon's AUTH-vs-STLS message dynamically, so the same identity authenticates over
     * this plain RSA-AUTH port. Holds no lock itself — callers order locks (heavy → monitor).
     */
    private fun connectLoopback(context: Context): Boolean {
        val port = AppPreferences(context).getLoopbackAdbPort()
        val mgr = AdbConnectionManager.getInstance(context)
        return runCatching { mgr.connect("127.0.0.1", port) }
            .onFailure { AppLogger.d(TAG, "Loopback :$port unavailable (unarmed/refused): ${it.message}") }
            .getOrDefault(false)
            .also { if (it) AppLogger.i(TAG, "Connected over loopback tcpip :$port (works off-WiFi)") }
    }

    /**
     * Arms the persistent classic-tcpip loopback listener so future connects work OFF-WiFi.
     *
     * Opens the `tcpip:<port>` adb service → adbd restarts listening on `0.0.0.0:<port>` (reachable on
     * loopback, which is always up). Because the restart DROPS the live connection, this is disruptive
     * and must run only at a SAFE idle moment — NEVER while recording. It needs a base connection to arm
     * through, which comes via Wireless Debugging, so WiFi + WD must be available ONCE; after arming,
     * calls record off-WiFi until the next reboot (tcpip mode clears on reboot → re-arm on next
     * connectivity). No-op (returns true) if the loopback port is already armed and reachable.
     *
     * Lock order: takes [heavyOperationLock] FIRST, then [ensureConnected] acquires the AdbShell
     * monitor — matching the recorder launcher's heavy→monitor order so the two never deadlock.
     *
     * @return true if the loopback listener is armed and reachable after the call.
     */
    fun armLoopbackIfNeeded(context: Context): Boolean = synchronized(heavyOperationLock) {
        if (connectLoopback(context)) {
            AppLogger.i(TAG, "Loopback already armed & reachable — nothing to do")
            return@synchronized true
        }
        // Need a base connection to arm through — bootstrap via Wireless Debugging directly (NOT
        // ensureConnected, which in offline mode is loopback-only and would just fail here). This is the
        // one deliberate, transient WD use in offline mode; applyWdPolicy turns WD back off once armed.
        if (!connectViaWirelessDebugging(context)) {
            AppLogger.i(TAG, "Cannot arm loopback — no base connection (needs WiFi + Wireless debugging once)")
            return@synchronized false
        }
        // ensureConnected may itself have landed us on loopback already (nothing left to arm).
        if (connectLoopback(context)) return@synchronized true

        val port = AppPreferences(context).getLoopbackAdbPort()
        val mgr = AdbConnectionManager.getInstance(context)
        AppLogger.i(TAG, "Arming loopback tcpip on :$port (adbd will restart)…")
        // Fire the tcpip: arm WITHOUT blocking. adbd restarts on receiving the OPEN and kills this very
        // connection, so reading the stream's response can stall forever (the read never gets an EOF when
        // the socket dies mid-flight). Opening the stream is what arms adbd; do it on a daemon thread with
        // a hard cap so a stalled open/close can never hang the caller.
        armFireThread(mgr, port)

        Thread.sleep(TCPIP_RESTART_WAIT_MS)
        runCatching { mgr.disconnect() }.onFailure { AppLogger.d(TAG, "post-arm disconnect ignored: ${it.message}") }
        Thread.sleep(POST_DISCONNECT_WAIT_MS)

        val armed = connectLoopback(context)
        AppLogger.i(TAG, "Loopback arm result on :$port = $armed")
        armed
    }

    /**
     * Opens `tcpip:<port>` to arm adbd, on a bounded daemon thread. The open triggers adbd's restart;
     * we neither read the response (the connection dies mid-read) nor wait beyond [ARM_FIRE_CAP_MS]
     * (a stalled open must not hang arming). Any leftover thread is a daemon and self-reaps.
     */
    private fun armFireThread(mgr: AdbConnectionManager, port: Int) {
        val t = Thread {
            runCatching { mgr.openStream("tcpip:$port").close() }
                .onFailure { AppLogger.d(TAG, "arm open/close ended: ${it.message} (adbd restarting — expected)") }
        }.apply { isDaemon = true; name = "cv-arm-tcpip" }
        t.start()
        t.join(ARM_FIRE_CAP_MS)
        if (t.isAlive) AppLogger.d(TAG, "arm open did not return within ${ARM_FIRE_CAP_MS}ms; proceeding (request already sent)")
    }

    /**
     * Closes the classic-tcpip listener (reverting adbd to USB mode via the `usb:` service — the device
     * side of `adb usb`), so the open port doesn't linger until reboot after the user turns OFF offline
     * recording. Best-effort + bounded (adbd restarts on the request, so it can stall); if it can't run
     * now, the port simply closes on the next reboot. Call OFF the main thread.
     */
    fun disarmLoopback(context: Context) = synchronized(heavyOperationLock) {
        val mgr = AdbConnectionManager.getInstance(context)
        if (!mgr.isConnected && !ensureConnected(context)) {
            AppLogger.i(TAG, "disarmLoopback: no connection to send usb: (port closes on next reboot)")
            return@synchronized
        }
        AppLogger.i(TAG, "Disarming loopback tcpip (reverting adbd to usb mode)…")
        val t = Thread {
            runCatching { mgr.openStream("usb:").close() }
                .onFailure { AppLogger.d(TAG, "usb: revert ended: ${it.message} (adbd restarting — expected)") }
        }.apply { isDaemon = true; name = "cv-disarm-tcpip" }
        t.start()
        t.join(ARM_FIRE_CAP_MS)
        Thread.sleep(TCPIP_RESTART_WAIT_MS)
        runCatching { mgr.disconnect() }.onFailure { AppLogger.d(TAG, "post-disarm disconnect ignored: ${it.message}") }
    }

    // ---- Wireless-debugging helpers ----

    /** Returns true if the app currently holds WRITE_SECURE_SETTINGS. */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Reads the adb_wifi_enabled global setting (Wireless debugging). */
    fun isWirelessDebuggingEnabled(context: Context): Boolean =
        runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        }.getOrDefault(false)

    /**
     * Turns Wireless debugging on by writing adb_wifi_enabled=1. Requires WRITE_SECURE_SETTINGS.
     * Returns true if the write succeeded (or it was already on).
     */
    fun enableWirelessDebugging(context: Context): Boolean {
        if (isWirelessDebuggingEnabled(context)) return true
        if (!hasWriteSecureSettings(context)) return false
        return runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        }.onFailure { AppLogger.e(TAG, "Failed to enable Wireless debugging", it) }.isSuccess
    }

    /**
     * Turns Wireless debugging OFF by writing adb_wifi_enabled=0. Requires WRITE_SECURE_SETTINGS.
     * Used by the persistent-server WD policy to keep WD off between uses once the daemon's binder is
     * connected (the daemon needs no ADB at record time). Returns true if WD is off after the call.
     *
     * NOTE: this drops the app's embedded ADB connection — only safe once the recorder daemon binder
     * is already connected (recording then flows over binder, not ADB).
     */
    fun disableWirelessDebugging(context: Context): Boolean {
        if (!isWirelessDebuggingEnabled(context)) return true
        if (!hasWriteSecureSettings(context)) return false
        return runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        }.onFailure { AppLogger.e(TAG, "Failed to disable Wireless debugging", it) }.isSuccess
    }

    /**
     * While connected, grant ourselves WRITE_SECURE_SETTINGS via our own ADB shell so we can
     * re-enable Wireless debugging on future boots. Idempotent; no-op once already granted.
     */
    private fun grantSecureSettingsIfNeeded(context: Context) {
        if (hasWriteSecureSettings(context)) return
        runCatching {
            val pkg = context.packageName
            openShell(context, "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS").use { s ->
                s.openInputStream().use { it.readBytes() }   // drain to let the command complete
            }
            AppLogger.i(TAG, "Requested self-grant of WRITE_SECURE_SETTINGS via ADB shell")
        }.onFailure { AppLogger.w(TAG, "Self-grant of WRITE_SECURE_SETTINGS failed: ${it.message}") }
    }

    /**
     * Silently re-grants WRITE_SECURE_SETTINGS after an install-over dropped it — but ONLY over a
     * transport that is ALREADY up, so it never restarts adbd (which would kill a warm daemon). This is
     * the common post-update state: the daemon survived the update (recording still flows over its
     * binder) and Wireless debugging is still ON (it couldn't be turned off without the very grant we
     * lost), so [ensureConnected] connects with NO `adb_wifi` write and self-grants via
     * [grantSecureSettingsIfNeeded]. If nothing safe is up (WD off + loopback not armed), we do NOTHING
     * here — enabling WD would churn adbd — and the Home banner asks the user to toggle WD once.
     *
     * Call OFF the main thread. Returns true if the grant is present afterwards.
     */
    fun tryHealWriteSecureSettings(context: Context): Boolean {
        if (hasWriteSecureSettings(context)) return true
        val offline = AppPreferences(context).isOfflineRecordingEnabled()
        // Heal only over an already-live transport — never toggle WD here (adbd restart risks the daemon).
        val transportUp = isWirelessDebuggingEnabled(context) || (offline && isLoopbackArmed(context))
        if (!transportUp) {
            AppLogger.i(TAG, "WRITE_SECURE_SETTINGS missing but no non-churning transport up; leaving for user WD toggle")
            return false
        }
        AppLogger.i(TAG, "WRITE_SECURE_SETTINGS missing; healing over live transport (WD/loopback already up)")
        ensureConnected(context)   // connects without a WD write and self-grants via grantSecureSettingsIfNeeded
        val healed = hasWriteSecureSettings(context)
        if (healed) {
            AppLogger.i(TAG, "WRITE_SECURE_SETTINGS re-granted via live transport (no adbd churn)")
        } else {
            AppLogger.w(TAG, "WRITE_SECURE_SETTINGS heal did NOT land (ADB connect/grant failed); recording still works while the daemon is warm")
        }
        return healed
    }
}
