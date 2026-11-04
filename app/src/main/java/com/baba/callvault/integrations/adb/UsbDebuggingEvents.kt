/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The phone's state at the moment USB debugging went off, so a report can say what caused it.
 *
 * [chargingAdb] is Honor/Huawei's "Allow ADB debugging in 'Charge only' mode" (`allow_charging_adb`). With it
 * off, their framework writes `adb_enabled=0` whenever USB sits in charge-only mode — which is every moment the
 * phone is unplugged. Null where the setting does not exist (every other make).
 */
data class UsbOffSnapshot(
    val screenOn: Boolean,
    val locked: Boolean,
    val plugged: String,
    val usb: String,
    val wifi: WifiState,
    val chargingAdb: Int?,
) {
    override fun toString(): String =
        "screen=${if (screenOn) "on" else "off"} locked=$locked power=$plugged usb=[$usb] wifi=$wifi " +
            "allow_charging_adb=${chargingAdb ?: "absent"}"

    companion object {
        fun capture(context: Context): UsbOffSnapshot {
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return UsbOffSnapshot(
                screenOn = runCatching { power.isInteractive }.getOrDefault(true),
                locked = runCatching { keyguard.isKeyguardLocked }.getOrDefault(false),
                plugged = pluggedOf(context),
                usb = usbStateOf(context),
                wifi = WifiState.of(context),
                chargingAdb = chargingAdb(context),
            )
        }

        /** Honor/Huawei's `allow_charging_adb`, or null where the phone has no such setting. */
        fun chargingAdb(context: Context): Int? = runCatching {
            Settings.Global.getString(context.contentResolver, CHARGING_ADB)?.trim()?.toIntOrNull()
        }.getOrNull()

        private fun pluggedOf(context: Context): String {
            val battery = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull() ?: return "?"
            return when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                0 -> "battery"
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "?"
            }
        }

        /** The sticky USB_STATE broadcast's flags: connected, configured, and each function that is on. */
        private fun usbStateOf(context: Context): String {
            val state = runCatching {
                context.registerReceiver(null, IntentFilter(USB_STATE))
            }.getOrNull() ?: return "?"
            val extras = state.extras ?: return "?"
            return extras.keySet().sorted()
                .filter { extras.get(it) == true }
                .joinToString(",")
        }

        const val CHARGING_ADB = "allow_charging_adb"
        private const val USB_STATE = "android.hardware.usb.action.USB_STATE"
    }
}

/**
 * An always-on record of every time USB debugging went off, and what CallVault did about it.
 *
 * Always on, like the setup journal, because the users this matters to are the ones who never switched debug
 * logging on — the 2026-11-04 Honor report carried no recorder lines at all. Small and capped: a line per event,
 * plus the phone's own log lines from around it once they can be read.
 *
 * Also holds two short lists: events whose system log has not been captured yet, and when CallVault last
 * switched USB debugging back on by itself (for [UsbDebuggingRestorePolicy]'s loop guard).
 */
object UsbDebuggingEvents {

    private const val FILE_NAME = "usb_debugging_events.txt"
    private const val PREFS = "cv_usb_debugging_events"
    private const val KEY_PENDING = "pending_capture"
    private const val KEY_RESTORES = "restores"
    private const val MAX_BYTES = 128 * 1024
    private const val MAX_PENDING = 5
    private const val KEEP_LIST = 10

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun record(context: Context, line: String) = synchronized(lock) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            file.appendText("${stamp.format(Date())} $line\n")
            if (file.length() > MAX_BYTES) {
                val text = file.readText()
                file.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        }
        Unit
    }

    fun readForReport(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    fun addPending(context: Context, atMs: Long) = edit(context, KEY_PENDING) { (it + atMs).takeLast(MAX_PENDING) }

    fun pending(context: Context): List<Long> = list(context, KEY_PENDING)

    fun clearPending(context: Context, atMs: Long) = edit(context, KEY_PENDING) { it - atMs }

    fun addRestore(context: Context, atMs: Long) = edit(context, KEY_RESTORES) { (it + atMs).takeLast(KEEP_LIST) }

    fun recentRestores(context: Context): List<Long> = list(context, KEY_RESTORES)

    private fun list(context: Context, key: String): List<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }

    private fun edit(context: Context, key: String, change: (List<Long>) -> List<Long>) = synchronized(lock) {
        val next = change(list(context, key))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, next.joinToString(","))
            .apply()
    }
}
