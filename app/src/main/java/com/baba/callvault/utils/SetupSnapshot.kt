/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.WifiState
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.ShizukuBackend

/**
 * One line, written to the always-on setup journal every time a setup screen is shown, saying what the phone looked
 * like at that moment. BabApps item 92: a Pixel 7 reporter was stuck at "Let's get set up" and his report could
 * not say which mode he was in, whether the Wireless debugging setting was readable, whether Shizuku was running
 * or what ROM build it was. Each field answers one of those questions.
 *
 * Nothing here is personal: a screen name, a mode, three Shizuku facts, a Wi-Fi state, the raw value of one system
 * setting and the ROM build. No numbers, names, networks or addresses.
 */
object SetupSnapshot {

    /** Tag for these lines; named in [SetupJournalPolicy] and never thinned there. */
    const val TAG = "CV:SetupScreen"

    /** Tag for mode changes: kept even while the journal is sealed, so a change months later is not lost. */
    const val MODE_TAG = "CV:SetupMode"

    class Facts(
        val screen: String,
        /** STANDALONE or SHIZUKU. */
        val mode: String,
        /** `adb_wifi_enabled` as read: -1 = the key is absent (hidden), 0 = off, 1 = on. */
        val wirelessDebuggingRaw: String,
        val wifi: String,
        val romDisplay: String,
        val romId: String,
        val android: String,
        val shizukuInstalled: String,
        val shizukuRunning: String,
        val shizukuPermission: String,
        val recorderConnected: String,
    )

    /** The line itself. Pure, so a test can hold it to the facts the reviewer asked for. */
    fun format(f: Facts): String =
        "Setup screen shown: ${f.screen} | mode=${f.mode} | adb_wifi_enabled=${f.wirelessDebuggingRaw} (-1 = key absent) | " +
            "wifi=${f.wifi} | rom=${f.romDisplay} (${f.romId}) ${f.android} | " +
            "shizuku: installed=${f.shizukuInstalled} running=${f.shizukuRunning} permission=${f.shizukuPermission} | " +
            "recorder connected=${f.recorderConnected}"

    /** Reads every fact. Each read is its own guard: one that throws becomes "?" and never hides the others. */
    fun collect(context: Context, screen: String): Facts {
        fun <T> read(block: () -> T): String = runCatching { block().toString() }.getOrDefault("?")
        return Facts(
            screen = screen,
            mode = read { AppPreferences(context).getPrivilegedMode().name },
            wirelessDebuggingRaw = read {
                Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", -1)
            },
            wifi = read { WifiState.of(context).name },
            romDisplay = read { safeBuild(Build.DISPLAY) },
            romId = read { safeBuild(Build.ID) },
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            shizukuInstalled = read { ShizukuBackend.isInstalled(context) },
            shizukuRunning = read { ShizukuBackend.isRunning() },
            shizukuPermission = read { ShizukuBackend.hasPermission() },
            recorderConnected = read { RecorderConnection.isConnected },
        )
    }

    /**
     * A build id with its dots turned into underscores. Found on the emulator: the log redactor reads
     * `DP11.261030.003` as a phone number (nine digits split by dots) and printed `[PHONE_REDACTED]` in its place,
     * which would have blanked the very field asked for. Underscores are not number separators to it.
     */
    fun safeBuild(build: String): String = build.replace('.', '_')

    /** Collects and logs. Call from a worker thread: the reads touch settings and Shizuku. */
    fun record(context: Context, screen: String) {
        runCatching { AppLogger.i(TAG, format(collect(context, screen))) }
    }

    /**
     * Who asked for a mode change, for the journal: the first caller outside [RecorderBackend] itself, as
     * `Class.method`. A mode that changes by itself (an auto-heal, a stale-state repair) reads differently from
     * one the user chose in Settings, and the journal needs to tell them apart.
     */
    fun triggerOf(stack: Array<StackTraceElement>): String {
        val frame = stack.firstOrNull {
            !it.className.startsWith("com.baba.callvault.server.RecorderBackend") &&
                !it.className.startsWith("com.baba.callvault.utils.SetupSnapshot") &&
                !it.className.startsWith("java.") && !it.className.startsWith("kotlin.") &&
                !it.className.startsWith("dalvik.") && !it.className.startsWith("android.")
        } ?: return "unknown"
        return frame.className.substringAfterLast('.') + "." + frame.methodName
    }

    fun modeChange(from: String, to: String, trigger: String): String =
        "Mode change: $from -> $to, triggered by $trigger"
}
