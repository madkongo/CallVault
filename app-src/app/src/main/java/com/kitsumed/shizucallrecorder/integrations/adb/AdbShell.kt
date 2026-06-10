/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.integrations.adb

import android.content.Context
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger
import io.github.muntashirakon.adb.AdbStream

/** Thin facade over the embedded ADB connection for the recording pipeline. */
object AdbShell {
    private const val TAG = "SCR:AdbShell"
    private const val CONNECT_SETTLE_MS = 2500L
    /** Reduced from 25 s so the recording path fails fast instead of hanging while falsely appearing to record. */
    private const val MDNS_TIMEOUT_MS = 12_000L
    /** How long to wait for adbd to start advertising after we toggle Wireless debugging on. */
    private const val WD_START_WAIT_MS = 4_000L

    /**
     * Ensures the ADB connection is up (mDNS-discover the connect port, connect, settle).
     * Call off main thread. On success, persists the "ADB paired" flag so the onboarding gate
     * is not shown again on subsequent launches (a live connection only exists per-process).
     *
     * If Wireless debugging is off (e.g. after an OEM reboot) and the app holds
     * WRITE_SECURE_SETTINGS, it re-enables Wireless debugging before starting mDNS discovery.
     *
     * **@Synchronized**: when a call wakes the app, the launch auto-connect (ShizuApplication) and
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
        // Re-enable Wireless debugging if the OEM turned it off on reboot (needs WRITE_SECURE_SETTINGS).
        if (!isWirelessDebuggingEnabled(context) && enableWirelessDebugging(context)) {
            AppLogger.i(TAG, "Re-enabled Wireless debugging; waiting for adbd to advertise…")
            Thread.sleep(WD_START_WAIT_MS)
        }
        val port = AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, MDNS_TIMEOUT_MS) ?: return false
        val ok = mgr.connect("127.0.0.1", port)
        if (ok) {
            Thread.sleep(CONNECT_SETTLE_MS)
            AppPreferences(context).setAdbPaired(true)
            grantSecureSettingsIfNeeded(context)
        }
        return ok
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
     * Opens an ADB localabstract socket by [name]. The full `localabstract:` prefix is added automatically.
     *
     * @param context App context.
     * @param name    The abstract socket name (without the "localabstract:" prefix).
     * @return An [AdbStream] connected to the abstract socket.
     */
    fun openLocalAbstract(context: Context, name: String): AdbStream =
        AdbConnectionManager.getInstance(context).openStream("localabstract:$name")

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
}
