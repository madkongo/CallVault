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

    /** How long Wireless debugging stays off in a restart cycle, so AdbService sees a real change. */
    private const val WD_CYCLE_OFF_MS = 1_500L

    /** How long a debugging-switch change gets to finish before adbd is started again. */
    private const val REVIVE_SETTLE_MS = 2_000L

    /** How long to wait for adbd to report running after a revival. */
    private const val ADBD_REVIVE_WAIT_MS = 5_000L
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
     * Hard cap on one ADB connect INCLUDING its CNXN/AUTH handshake. The library's connect has no
     * timeout of its own, so without this a handshake interrupted by an adbd restart parks the caller
     * forever while it holds this object's monitor and (usually) [heavyOperationLock]. Generous enough
     * for a healthy local handshake, which is milliseconds over loopback.
     */
    private const val CONNECT_BUDGET_MS = 8_000L

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
    /**
     * What to do with Wireless debugging now that the daemon is up — see [WirelessDebuggingPolicy].
     *
     * Reads the two things that keep `adbd` alive independently of Wireless debugging: USB debugging,
     * and our own loopback listener.
     */
    fun wirelessDebuggingPlan(context: Context): WirelessDebuggingPlan = WirelessDebuggingPolicy.plan(
        isUsbDebuggingEnabled = isUsbDebuggingEnabled(context),
        isLoopbackArmed = isLoopbackArmed(context),
    )

    /**
     * Whether USB debugging is enabled. It keeps `adbd` running whether or not a cable is attached, so
     * it counts as a transport for the purposes of [wirelessDebuggingPlan].
     */
    fun isUsbDebuggingEnabled(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, "adb_enabled", 0) == 1
    }.getOrDefault(false)

    internal fun isLoopbackArmed(context: Context): Boolean =
        getSystemProperty("service.adb.tcp.port") == AppPreferences(context).getLoopbackAdbPort().toString()

    /**
     * Whether adbd is actually running, from Android's own `init.svc.adbd`.
     *
     * The switch settings are not evidence of this: turning USB debugging off stops adbd while Wireless
     * debugging still reads on (#39). Any app may read this property (`allow domain
     * init_service_status_prop`), unlike `service.adb.tls.port`.
     */
    fun adbdState(): AdbdState = AdbdState.of(getSystemProperty("init.svc.adbd"))

    /**
     * Brings adbd back when a debugging switch says it should be up and it is not. Blocking; call off the
     * main thread.
     *
     * Switching a user's Wireless debugging off and on again ends with it on, as they left it, and its
     * ownership is put back exactly — so #30's rule (never take away a switch the user set) still holds.
     *
     * @return what was decided, for the caller's log.
     */
    fun reviveAdbdIfStopped(
        context: Context,
        reason: String,
        /** False where CallVault must not switch Wireless debugging on from off — Shizuku mode. */
        mayEnable: Boolean = true,
    ): AdbdRevival {
        fun decideNow(): AdbdRevival {
            val prefs = AppPreferences(context)
            val userOff = prefs.wasWirelessDebuggingTurnedOffByUser() && !prefs.isWirelessDebuggingEnforced()
            return AdbdRevivalPolicy.decide(
                adbd = adbdState(),
                usbDebuggingOn = isUsbDebuggingEnabled(context),
                wirelessDebuggingOn = isWirelessDebuggingEnabled(context),
                wifi = WifiState.of(context),
                hasGrant = hasWriteSecureSettings(context),
                mayEnable = mayEnable && !userOff,
            )
        }
        // One revival at a time. The switch observer and the keep-alive both reach here within milliseconds of
        // USB debugging going off; seen on the emulator, both switched Wireless debugging on at once. The second
        // now waits, looks again, and finds adbd running.
        synchronized(reviveLock) {
        var decision = decideNow()
        if (decision == AdbdRevival.ENABLE_WIRELESS_DEBUGGING || decision == AdbdRevival.CYCLE_WIRELESS_DEBUGGING) {
            // Let a USB change that may still be running finish first, then look again. Starting adbd inside
            // it loses a race measured on the emulator (24 ms wide): init stops the adbd we just started.
            // Every caller can reach here within milliseconds of the change — the keep-alive's binder-death
            // relaunch does — so the wait lives here rather than in any one caller.
            Thread.sleep(REVIVE_SETTLE_MS)
            decision = decideNow()
        }
        when (decision) {
            AdbdRevival.NOTHING -> return decision
            AdbdRevival.NEEDS_WIFI, AdbdRevival.NO_GRANT -> {
                AppLogger.i(TAG, "adbd is stopped after $reason and cannot be revived now ($decision)")
                return decision
            }
            AdbdRevival.ENABLE_WIRELESS_DEBUGGING -> {
                AppLogger.i(TAG, "adbd is stopped after $reason; switching Wireless debugging on")
                enableWirelessDebugging(context)
            }
            AdbdRevival.CYCLE_WIRELESS_DEBUGGING -> {
                AppLogger.i(TAG, "adbd is stopped after $reason although Wireless debugging reads on; switching it off and on")
                val ours = AppPreferences(context).wasWirelessDebuggingEnabledByUs()
                markOwnWirelessDebuggingWrite(0)
                runCatching { android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0) }
                    .onFailure { AppLogger.w(TAG, "Could not switch Wireless debugging off to restart adbd: ${it.message}") }
                Thread.sleep(WD_CYCLE_OFF_MS)
                enableWirelessDebugging(context)
                // enableWirelessDebugging records the switch as ours; it was not necessarily.
                AppPreferences(context).setWirelessDebuggingEnabledByUs(ours)
            }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + ADBD_REVIVE_WAIT_MS
        while (adbdState() != AdbdState.RUNNING && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
        }
        AppLogger.i(TAG, "adbd after revival ($decision): ${adbdState()}")
        return decision
        }
    }

    /** Serialises [reviveAdbdIfStopped]. Its own lock, so a revival never blocks unrelated ADB work. */
    private val reviveLock = Any()

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
    private fun connectViaWirelessDebugging(context: Context): Boolean =
        connectViaWirelessDebuggingWithReason(context) == BaseConnect.CONNECTED

    /** Why the Wireless-debugging bootstrap did or did not produce a connection. */
    internal enum class BaseConnect {
        CONNECTED,
        NEEDS_WIRELESS_DEBUGGING,
        /** Wireless debugging is off and there is no Wi-Fi to turn it on over. */
        NO_WIFI,
        /** We switched it on and Android switched it straight back off — typically an untrusted network. */
        WIRELESS_DEBUGGING_REFUSED,
        NO_ADB_SERVICE,
        CONNECT_REFUSED,
    }

    @Synchronized
    private fun connectViaWirelessDebuggingWithReason(context: Context): BaseConnect {
        val mgr = AdbConnectionManager.getInstance(context)
        if (mgr.isConnected) return BaseConnect.CONNECTED
        // Re-enable Wireless debugging if the OEM turned it off on reboot (needs WRITE_SECURE_SETTINGS).
        if (!isWirelessDebuggingEnabled(context)) {
            if (!enableWirelessDebugging(context)) {
                if (WifiState.of(context) == WifiState.NOT_CONNECTED) return BaseConnect.NO_WIFI
                AppLogger.w(TAG, "Wireless debugging is off and could not be switched on")
                return BaseConnect.NEEDS_WIRELESS_DEBUGGING
            }
            AppLogger.i(TAG, "Re-enabled Wireless debugging; waiting for adbd to advertise…")
            Thread.sleep(WD_START_WAIT_MS)
            // Read our own write back. AOSP's AdbDebuggingManager puts adb_wifi_enabled back to 0 when it
            // will not run Wireless debugging — no Wi-Fi, or a network the user has not trusted, where it
            // shows its own "Allow on this network?" prompt instead. Waiting 12 s for an mDNS service that
            // cannot exist is how #39 spent three minutes before giving up. We cannot read
            // service.adb.tls.port instead: SELinux gives it only to adbd and system_server.
            if (!isWirelessDebuggingEnabled(context)) {
                AppLogger.w(TAG, "Android switched Wireless debugging back off after we turned it on (untrusted network or no Wi-Fi)")
                return BaseConnect.WIRELESS_DEBUGGING_REFUSED
            }
        }
        val port = AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, MDNS_TIMEOUT_MS)
            ?: return BaseConnect.NO_ADB_SERVICE
        // Bounded for the same reason as the loopback connect: this call runs the whole CNXN/AUTH
        // handshake with no timeout of its own, and it is reached from armLoopbackIfNeeded, which holds
        // heavyOperationLock throughout — so one stalled handshake here freezes every ADB operation in
        // the process. Seen on a fresh install (2026-07-30): onboarding's "Enabling off-Wi-Fi
        // recording…" spinner never returned, with eleven threads parked behind this lock.
        //
        // connect() also THROWS on an unpaired/unauthorised identity (AdbPairingRequiredException) or a
        // flaky TLS handshake (SSLProtocolException: CERTIFICATE_UNKNOWN). Callers branch on the
        // boolean — propagating crashed the app at onboarding's "Setup ADB" step — so it stays swallowed
        // to false inside the bounded worker.
        val ok = connectBounded(context, "Wireless debugging :$port") { mgr.connect("127.0.0.1", port) }
        if (!ok) return BaseConnect.CONNECT_REFUSED
        Thread.sleep(CONNECT_SETTLE_MS)
        AppPreferences(context).setAdbPaired(true)
        grantSecureSettingsIfNeeded(context)
        return BaseConnect.CONNECTED
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
     * Drops the ADB connection WITHOUT reconnecting, to unblock a caller wedged on a half-dead socket.
     *
     * A socket in `CLOSE_WAIT` (adbd hung up, we never closed our end) leaves a stream read blocked with
     * no timeout. If that read is inside [com.baba.callvault.server.RecorderServerLauncher.ensureServerRunning]
     * the blocked thread also holds [heavyOperationLock], so every later ADB operation queues behind it.
     * Closing the connection from another thread is what lets the blocked one unwind and release the
     * lock — the same technique `probeShellOnce` uses on its own hung read.
     *
     * **Deliberately NOT `@Synchronized`, and this is the whole point.** The thread we are trying to
     * rescue is typically blocked *inside* [ensureConnected], which holds this object's monitor — so a
     * synchronized rescue would wait on the very lock it exists to release, and deadlock instead of
     * recovering. Do not "tidy" this to match its neighbours.
     *
     * Does not reconnect either: whoever needs a connection builds a fresh one through
     * [ensureConnected], and reconnecting here would re-enter the code we are escaping.
     */
    fun dropConnection(context: Context) {
        runCatching { AdbConnectionManager.getInstance(context).disconnect() }
            .onFailure { AppLogger.d(TAG, "dropConnection ignored: ${it.message}") }
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
        return connectBounded(context, "loopback tcpip :$port") { mgr.connect("127.0.0.1", port) }
            .also { if (it) AppLogger.i(TAG, "Connected over loopback tcpip :$port (works off-WiFi)") }
    }

    /**
     * Runs an ADB [connect] under a hard time bound, because the library's connect has none.
     *
     * `AdbConnectionManager.connect` opens the socket and then waits out the whole ADB `CNXN`/`AUTH`
     * handshake. On this ROM `adbd` restarts on screen on/off transitions, and a TCP connect can succeed
     * against a listener whose `adbd` is mid-restart — so the handshake reply never arrives, the reader
     * dies, and nothing ever signals the waiter. The caller then parks **forever**, and because
     * [ensureConnected] is `@Synchronized` and its callers hold [heavyOperationLock], it parks holding
     * both — freezing every ADB operation in the process.
     *
     * That is not hypothetical: on 2026-07-30 it cost a device ~16 hours of silently missed recordings
     * overnight, when the daemon was reaped while idle and every relaunch after the bad roll piled up
     * behind the parked thread. See `2026-07-30-keepalive-rewarm-latch-wedge.md`.
     *
     * The work runs on a throwaway daemon thread and is abandoned on timeout — the same technique
     * [probeShellOnce] uses. Abandoning is safe *because it is a separate thread*: it never entered this
     * object's monitor, so the caller returning false releases both locks and the next attempt gets a
     * clean run. We also drop the connection so the stranded thread can unwind if it is able to.
     */
    private fun connectBounded(context: Context, what: String, connect: () -> Boolean): Boolean {
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            runCatching { ok.set(connect()) }
                .onFailure { AppLogger.d(TAG, "$what unavailable (unarmed/refused): ${it.message}") }
        }.apply { isDaemon = true; name = "cv-adb-connect" }
        worker.start()
        runCatching { worker.join(CONNECT_BUDGET_MS) }
        if (worker.isAlive) {
            AppLogger.w(TAG, "$what did not complete the ADB handshake within ${CONNECT_BUDGET_MS}ms (adbd restarting?) — abandoning this attempt")
            runCatching { AdbConnectionManager.getInstance(context).disconnect() }
            return false
        }
        return ok.get()
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
    fun armLoopbackIfNeeded(context: Context): Boolean =
        armLoopbackIfNeededWithReason(context) == LoopbackArm.ARMED

    /**
     * As [armLoopbackIfNeeded], but says **why** it failed, so the screen can too.
     *
     * The old boolean forced one message for every failure — "connect to Wi-Fi once, then try from
     * Settings" — which names a cause nobody checked. mirror176 read it in #30 while sitting on Wi-Fi
     * with Wireless debugging already on, and it told him nothing except that CallVault was confused.
     *
     * It also takes the ADB lease for the duration. Arming was the one path that did not, so any other
     * ADB user finishing mid-arm could switch Wireless debugging off underneath it.
     */
    fun armLoopbackIfNeededWithReason(context: Context): LoopbackArm =
        asAdbUser(context, "arming the loopback listener") {
            armLoopbackLocked(context)
        }

    private fun armLoopbackLocked(context: Context): LoopbackArm = synchronized(heavyOperationLock) {
        if (connectLoopback(context)) {
            AppLogger.i(TAG, "Loopback already armed & reachable — nothing to do")
            return@synchronized LoopbackArm.ARMED
        }
        // Need a base connection to arm through — bootstrap via Wireless Debugging directly (NOT
        // ensureConnected, which in offline mode is loopback-only and would just fail here). This is the
        // one deliberate, transient WD use in offline mode; applyWdPolicy turns WD back off once armed.
        val base = connectViaWirelessDebuggingWithReason(context)
        if (base != BaseConnect.CONNECTED) {
            AppLogger.i(TAG, "Cannot arm loopback — no base connection ($base)")
            return@synchronized when (base) {
                BaseConnect.NEEDS_WIRELESS_DEBUGGING -> LoopbackArm.NEEDS_WIRELESS_DEBUGGING
                BaseConnect.NO_WIFI -> LoopbackArm.NO_WIFI
                BaseConnect.WIRELESS_DEBUGGING_REFUSED -> LoopbackArm.WIRELESS_DEBUGGING_REFUSED
                else -> LoopbackArm.NO_ADB_SERVICE
            }
        }
        // ensureConnected may itself have landed us on loopback already (nothing left to arm).
        if (connectLoopback(context)) return@synchronized LoopbackArm.ARMED

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
        if (armed) LoopbackArm.ARMED else LoopbackArm.PORT_DID_NOT_COME_UP
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
        val prefs = AppPreferences(context)
        when (
            WirelessDebuggingEnableGate.decide(
                alreadyOn = isWirelessDebuggingEnabled(context),
                hasGrant = hasWriteSecureSettings(context),
                wifi = WifiState.of(context),
                userTurnedOff = prefs.wasWirelessDebuggingTurnedOffByUser(),
                enforced = prefs.isWirelessDebuggingEnforced(),
                userRequested = userRequest.get() == true,
            )
        ) {
            // Already on means it is the user's, not ours — recorded so nothing later mistakes it for a
            // switch we are entitled to undo (#30).
            WirelessDebuggingEnable.ALREADY_ON -> return true
            WirelessDebuggingEnable.NO_GRANT -> return false
            // The framework would put it straight back to 0, and on OxygenOS/One UI the attempt also
            // turned the user's USB debugging on and restarted adbd (#24, #39). See the gate.
            // The user switched it off and has not opted into CallVault overriding that. The notification
            // says recording is paused and offers to turn it back on.
            WirelessDebuggingEnable.RESPECT_USER -> {
                AppLogger.i(TAG, "Not switching Wireless debugging on: the user turned it off (override setting is off)")
                return false
            }
            WirelessDebuggingEnable.NO_WIFI -> {
                AppLogger.i(TAG, "Not switching Wireless debugging on: no Wi-Fi, so Android would refuse it")
                return false
            }
            WirelessDebuggingEnable.WRITE -> Unit
        }
        markOwnWirelessDebuggingWrite(1)
        val enabled = runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
        }.onFailure { AppLogger.e(TAG, "Failed to enable Wireless debugging", it) }.isSuccess
        if (enabled) {
            prefs.setWirelessDebuggingEnabledByUs(true)
            prefs.setWirelessDebuggingTurnedOffByUser(false)
        }
        return enabled
    }

    // -------- Telling our own writes apart from the user's
    //
    // CallVault turns Wireless debugging on and off itself, so a watcher on that setting cannot simply
    // react to every change — it would fight its own bootstrap, switching off the very thing it just
    // switched on. Recording each write lets a change be attributed: if it matches what we just wrote,
    // it is ours; anything else came from the user (or another app), and only then is it acted on.

    /**
     * Set while code runs on behalf of a button the user pressed — pairing, enabling off-Wi-Fi recording, the
     * notification's "turn it back on" — so a Wireless-debugging switch the user turned off may be switched on
     * for it. Thread-scoped because those paths reach [enableWirelessDebugging] through several layers on one
     * worker thread; everything else (the keep-alive, recovery) runs without it and respects the user.
     */
    private val userRequest = ThreadLocal<Boolean>()

    /** Runs [block] as an explicit user request. See [userRequest]. Blocking, like everything it wraps. */
    fun <T> asUserRequest(block: () -> T): T {
        val previous = userRequest.get()
        userRequest.set(true)
        try {
            return block()
        } finally {
            userRequest.set(previous)
        }
    }

    @Volatile private var ownWdWriteValue = -1
    @Volatile private var ownWdWriteAtMs = 0L

    /** How long a write stays attributable to us. Long enough to cover the settings round-trip. */
    private const val OWN_WRITE_WINDOW_MS = 10_000L

    private fun markOwnWirelessDebuggingWrite(value: Int) {
        ownWdWriteValue = value
        ownWdWriteAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** True when the current Wireless-debugging state is one CallVault itself just set. */
    fun didWeJustSetWirelessDebugging(enabled: Boolean): Boolean =
        ownWdWriteValue == (if (enabled) 1 else 0) &&
            android.os.SystemClock.elapsedRealtime() - ownWdWriteAtMs < OWN_WRITE_WINDOW_MS

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
        markOwnWirelessDebuggingWrite(0)
        val disabled = runCatching {
            android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 0)
        }.onFailure { AppLogger.e(TAG, "Failed to disable Wireless debugging", it) }.isSuccess
        if (disabled) AppPreferences(context).setWirelessDebuggingEnabledByUs(false)
        return disabled
    }

    /**
     * Runs [block] as an ADB user, switching Wireless debugging back off when the last user finishes.
     *
     * **Every entry point that may need the ADB connection should go through this.** Wireless debugging
     * is turned on in exactly one place ([connectViaWirelessDebugging]) but used to be turned off in
     * quite another — the recorder launcher, once the daemon's binder arrived. So every other caller
     * that needed a connection (the USB-default probe, the log collector, the updater, the permissions
     * screen) turned it on and left it on. Measured on the OP9: a reconnect with no launch after it, and
     * `adb_wifi_enabled` still 1 three minutes later — an open network port outliving its purpose.
     *
     * The release is deliberately conditional on being the last user: [disableWirelessDebugging] drops
     * the app's embedded ADB connection, so releasing eagerly could cut it out from under a recording
     * being armed on another thread.
     */
    fun <T> asAdbUser(context: Context, reason: String, block: () -> T): T {
        WirelessDebuggingLease.acquire()
        try {
            return block()
        } finally {
            val last = WirelessDebuggingLease.release()
            AppLogger.d(TAG, "ADB user done: $reason (last=$last)")
            if (last) releaseWirelessDebugging(context, reason)
        }
    }

    /**
     * Switches Wireless debugging off if it is on and it is safe to do so, saying why when it is not.
     *
     * "WD only when needed" applies to **CallVault's own** use of the switch. A switch the user turned
     * on is theirs: they may be using adb from a PC, and taking it away a second later — which is what
     * #30 reported — is the app fighting its owner. Ownership is recorded in [enableWirelessDebugging]
     * and consulted here.
     *
     * It also respects the one case where switching off is destructive: `adbd` stops when its LAST transport goes away, and the daemon is a child of an adbd
     * shell, so dropping WD while it is the only transport kills the daemon. Measured on a Galaxy
     * S24 FE as a six-round relaunch loop that never reached "ready to record".
     */
    fun releaseWirelessDebugging(context: Context, reason: String) {
        if (!isWirelessDebuggingEnabled(context)) {
            AppLogger.d(TAG, "Nothing to release after $reason; Wireless debugging is already off")
            return
        }

        val plan = wirelessDebuggingPlan(context)
        if (!AppPreferences(context).wasWirelessDebuggingEnabledByUs()) {
            AppLogger.i(TAG, "Leaving Wireless debugging on after $reason: the user switched it on, not us")
            return
        }
        if (WirelessDebuggingPolicy.mustKeepWirelessDebugging(plan)) {
            AppLogger.i(TAG, "Keeping Wireless debugging on after $reason: it is adbd's only transport")
            return
        }
        if (disableWirelessDebugging(context)) {
            AppLogger.i(TAG, "Wireless debugging disabled after $reason ($plan)")
        } else {
            AppLogger.w(TAG, "Could not disable Wireless debugging after $reason (missing WRITE_SECURE_SETTINGS?)")
        }
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
