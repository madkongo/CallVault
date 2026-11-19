/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/** What [WirelessDebuggingApproval.approveCurrentNetwork] did. Sent to the app by name. */
enum class NetworkApproval {
    APPROVED,

    /** `cmd wifi status` named no network, or only placeholders: not on Wi-Fi, or the shell could not read it. */
    NO_WIFI_IDENTITY,

    /** No `adb` system service, or no `allowWirelessDebugging` of a shape we know. */
    UNSUPPORTED,

    FAILED,
}

/** The network Android's "Allow wireless debugging on this network?" prompt asks about. */
data class WifiIdentity(val ssid: String, val bssid: String)

/**
 * Approves the current Wi-Fi network for Wireless debugging, the way the prompt's Allow button does, so
 * Android never shows "Allow wireless debugging on this network?".
 *
 * Runs in the shell-uid recorder process. The prompt's button calls `IAdbManager.allowWirelessDebugging`,
 * guarded only by `MANAGE_DEBUGGING`, which the shell holds (`packages/Shell/AndroidManifest.xml`). Android
 * then switches Wireless debugging on for this network; no prompt needs to be showing (`AdbDebuggingManager`,
 * `MSG_ADBWIFI_ALLOW`). Read from AOSP 16 and 17; see docs/dev-notes/2026-10-21-auto-approve-new-wifi-network.md.
 */
object WirelessDebuggingApproval {

    private const val TAG = "CV:WdApproval"
    private const val CMD = "/system/bin/cmd"
    private const val SETTINGS = "/system/bin/settings"
    private const val GETPROP = "/system/bin/getprop"
    private const val DUMPSYS = "/system/bin/dumpsys"
    private const val ADB_DUMP_HEADER = "ADB MANAGER STATE"
    private const val KEYSTORE_FIELD = "keystore"
    private val BSSID = Regex("""(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:])""")
    private const val WD_SETTING = "adb_wifi_enabled"
    private const val TIMEOUT_SEC = 10L

    /** What Android reports when it will not say which network this is. */
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private const val PLACEHOLDER_BSSID = "02:00:00:00:00:00"

    /** `WifiInfo.toString()`: `SSID: "name", BSSID: aa:bb:cc:dd:ee:ff, MAC: …`. The SSID keeps its quotes. */
    private val WIFI_INFO = Regex("""WifiInfo: SSID: (.*?), BSSID: ([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})""")

    /**
     * @param alwaysAllow also add the network to Android's trusted list, as ticking "Always allow" does.
     *   False approves this visit only.
     */
    fun approveCurrentNetwork(alwaysAllow: Boolean): NetworkApproval {
        val identity = currentIdentity()
        if (identity == null) {
            AppLogger.i(TAG, "No Wi-Fi network to approve")
            return NetworkApproval.NO_WIFI_IDENTITY
        }
        val result = approveIdentity(identity, alwaysAllow)
        // The network is named in the daemon's own log only, never sent anywhere.
        if (result == NetworkApproval.APPROVED) {
            AppLogger.i(TAG, "Approved Wi-Fi ${identity.ssid} for Wireless debugging (alwaysAllow=$alwaysAllow)")
        }
        return result
    }

    /**
     * Shizuku mode (review N15): approves the current network always-allow, then puts Wireless debugging back the
     * way it was. See [KeptSwitchApprover]. Returns a [KeptSwitchResult], encoded.
     *
     * @param knownKeys [BssidPrivacy.key]s of access points the app has already had approved.
     * @param salt the app's per-install salt for those keys.
     */
    fun approveKeepingSwitch(knownKeys: Set<String>, salt: String): String =
        keptSwitchApprover().run(knownKeys, salt).encode()

    /** The Shizuku-mode approver, wired to this process's shell commands. */
    fun keptSwitchApprover(): KeptSwitchApprover = KeptSwitchApprover(
        identity = ::currentIdentity,
        trustedBssids = ::readTrustedBssids,
        readWirelessDebugging = ::readWirelessDebugging,
        adbdListensOnTcp = ::adbdListensOnTcp,
        approve = ::approveIdentity,
        writeWirelessDebuggingOff = ::writeWirelessDebuggingOff,
    )

    /**
     * Android's trusted list for Wireless debugging, read through `dumpsys adb`: the shell holds DUMP, and the dump
     * is made by system_server, which reads `/data/misc/adb/adb_temp_keys.xml` itself (`AdbDebuggingManager.dump`,
     * field "keystore", AOSP android14-release). Null when it cannot be read.
     */
    fun readTrustedBssids(): Set<String>? {
        val trusted = parseTrustedBssids(runCommand(DUMPSYS, "adb"))
        if (trusted == null) AppLogger.w(TAG, "Could not read Android's trusted Wi-Fi list from 'dumpsys adb'")
        return trusted
    }

    /**
     * The access points in a `dumpsys adb` output's keystore (`<wifiAP bssid="…"/>`), lower case. Matches addresses
     * rather than XML, so a keystore written as binary XML (Android 12+) reads the same. Empty when the dump has no
     * keystore (no file yet: nothing trusted); null when the output is not an adb dump at all (refused, failed).
     */
    fun parseTrustedBssids(dump: String?): Set<String>? {
        if (dump == null || !dump.contains(ADB_DUMP_HEADER)) return null
        val keystore = dump.indexOf(KEYSTORE_FIELD).takeIf { it >= 0 } ?: return emptySet()
        return BSSID.findAll(dump.substring(keystore)).map { it.value.lowercase() }.toSet()
    }

    /**
     * Whether adbd listens on a tcp port: `service.adb.tcp.port` (set when adbd binds, e.g. after `adb tcpip`, which
     * is Shizuku's tcp mode) or `persist.adb.tcp.port` (adbd binds it at start). Either positive is enough.
     */
    fun adbdListensOnTcp(): Boolean =
        isTcpPort(runCommand(GETPROP, "service.adb.tcp.port"), runCommand(GETPROP, "persist.adb.tcp.port"))

    /** `getprop` outputs of the two port properties to "a tcp port is set". */
    fun isTcpPort(servicePort: String?, persistPort: String?): Boolean =
        listOf(servicePort, persistPort).any { (it?.trim()?.toIntOrNull() ?: 0) > 0 }

    /** The network the phone is connected to, as the shell sees it, or null. */
    fun currentIdentity(): WifiIdentity? = parseWifiStatus(runCmdWifiStatus())

    /** Calls `allowWirelessDebugging` for [identity]; any exception is [NetworkApproval.FAILED], logged. */
    fun approveIdentity(identity: WifiIdentity, alwaysAllow: Boolean): NetworkApproval =
        runCatching { callAllow(identity, alwaysAllow) }
            .onFailure { AppLogger.w(TAG, "allowWirelessDebugging failed: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(NetworkApproval.FAILED)

    /**
     * `adb_wifi_enabled` as the shell reads it: 1, 0, or null when it cannot be read. Never set reads as 0, which is
     * how Android itself reads it (`Settings.Global.getInt(…, 0)` in AdbService).
     */
    fun readWirelessDebugging(): Int? = parseSettingValue(runCommand(SETTINGS, "get", "global", WD_SETTING))

    /** `settings get` output to 1, 0 or null (unreadable). */
    fun parseSettingValue(output: String?): Int? = when (output?.trim()) {
        "1" -> 1
        "0", "null" -> 0
        else -> null
    }

    /** Writes Wireless debugging off, as shell. True when the command ran cleanly. */
    fun writeWirelessDebuggingOff(): Boolean = runCatching {
        val process = ProcessBuilder(SETTINGS, "put", "global", WD_SETTING, "0").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val ok = finished && process.exitValue() == 0 && output.isBlank()
        if (!ok) AppLogger.w(TAG, "'settings put global $WD_SETTING 0' did not succeed: ${output.trim()}")
        ok
    }.getOrElse {
        AppLogger.w(TAG, "'settings put' could not run: ${it.message}")
        false
    }

    /** The network `cmd wifi status` reports as connected, or null when there is none it will name. */
    fun parseWifiStatus(output: String): WifiIdentity? {
        val match = WIFI_INFO.find(output) ?: return null
        val ssid = match.groupValues[1].trim()
        val bssid = match.groupValues[2].lowercase()
        if (ssid.isEmpty() || ssid == UNKNOWN_SSID || ssid == "\"\"") return null
        if (bssid == PLACEHOLDER_BSSID) return null
        return WifiIdentity(ssid = ssid, bssid = bssid)
    }

    /**
     * The argument list for the shape of `allowWirelessDebugging` this phone has, or null for one we do not
     * know. Android 11-16: `(boolean alwaysAllow, String bssid)`. Android 17: `(boolean, String bssid, String ssid)`.
     */
    fun argumentsFor(parameterCount: Int, identity: WifiIdentity, alwaysAllow: Boolean): Array<Any>? =
        when (parameterCount) {
            2 -> arrayOf(alwaysAllow, identity.bssid)
            3 -> arrayOf(alwaysAllow, identity.bssid, identity.ssid)
            else -> null
        }

    private fun callAllow(identity: WifiIdentity, alwaysAllow: Boolean): NetworkApproval {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "adb")
        if (binder == null) {
            AppLogger.w(TAG, "No adb system service")
            return NetworkApproval.UNSUPPORTED
        }
        val adbManager = Class.forName("android.debug.IAdbManager\$Stub")
            .getMethod("asInterface", Class.forName("android.os.IBinder"))
            .invoke(null, binder) ?: return NetworkApproval.UNSUPPORTED
        val method = adbManager.javaClass.methods
            .filter { it.name == "allowWirelessDebugging" }
            .firstOrNull { argumentsFor(it.parameterCount, identity, alwaysAllow) != null }
        if (method == null) {
            AppLogger.w(TAG, "allowWirelessDebugging has no shape we know")
            return NetworkApproval.UNSUPPORTED
        }
        method.invoke(adbManager, *argumentsFor(method.parameterCount, identity, alwaysAllow)!!)
        return NetworkApproval.APPROVED
    }

    private fun runCmdWifiStatus(): String = runCommand(CMD, "wifi", "status").orEmpty()

    /** Output of a shell command, or null when it could not run or did not finish in time. */
    private fun runCommand(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else output
    }.getOrElse {
        AppLogger.w(TAG, "'${command.joinToString(" ")}' could not run: ${it.message}")
        null
    }
}
