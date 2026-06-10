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
import io.github.muntashirakon.adb.AdbStream

/** Thin facade over the embedded ADB connection for the recording pipeline. */
object AdbShell {
    private const val CONNECT_SETTLE_MS = 2500L
    private const val MDNS_TIMEOUT_MS = 25_000L

    /**
     * Ensures the ADB connection is up (mDNS-discover the connect port, connect, settle).
     * Call off main thread. On success, persists the "ADB paired" flag so the onboarding gate
     * is not shown again on subsequent launches (a live connection only exists per-process).
     *
     * @return true if already connected or newly connected successfully; false on failure.
     */
    fun ensureConnected(context: Context): Boolean {
        val mgr = AdbConnectionManager.getInstance(context)
        if (mgr.isConnected) {
            AppPreferences(context).setAdbPaired(true)
            return true
        }
        val port = AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, MDNS_TIMEOUT_MS) ?: return false
        val ok = mgr.connect("127.0.0.1", port)
        if (ok) {
            Thread.sleep(CONNECT_SETTLE_MS)
            AppPreferences(context).setAdbPaired(true)
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
}
