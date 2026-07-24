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

/**
 * The device's **Default USB Configuration** (Developer options → "Default USB Configuration") — i.e.
 * the USB functions applied when the screen is unlocked (`screen_unlocked_functions`).
 *
 * **Why CallVault cares.** On OnePlus/Xiaomi/Samsung a DATA default (File transfer, etc.) makes the USB
 * gadget renegotiate on every screen on/off transition, which **restarts `adbd`** — and that kills the
 * shell-uid recorder daemon. If it happens mid-call (user locks the phone) the recording stops. Setting
 * the default to **"No data transfer / Charging only"** (no screen-unlocked functions) removes that
 * churn, so the daemon survives a screen lock and the recording continues. Confirmed on-device.
 *
 * Read via `dumpsys usb` and changed via `svc usb setScreenUnlockedFunctions <fn>`, both over the app's
 * embedded ADB shell (shell uid holds the privilege). Both are framework-level → OEM-agnostic.
 */
enum class UsbDefaultMode(
    /** Argument to `svc usb setScreenUnlockedFunctions` ("" = charging/off). */
    val svcArg: String,
) {
    /** "No data transfer / Charging only" — RECOMMENDED; the recorder survives a screen lock. */
    CHARGING(""),
    /** "File transfer / Android Auto" (MTP). */
    FILE_TRANSFER("mtp"),
    /** "PTP". */
    PTP("ptp"),
    /** "USB tethering" (RNDIS). */
    TETHERING("rndis"),
    /** "MIDI". */
    MIDI("midi"),
    /** Couldn't read / unrecognised value. */
    UNKNOWN(""),
}

object UsbDefaultConfig {

    private const val TAG = "CV:UsbDefault"

    /** The only mode in which recording reliably survives a mid-call screen lock. */
    val RECOMMENDED = UsbDefaultMode.CHARGING

    /** Modes offered to the user in the picker (UNKNOWN is never a choice). */
    val SELECTABLE = listOf(
        UsbDefaultMode.CHARGING,
        UsbDefaultMode.FILE_TRANSFER,
        UsbDefaultMode.PTP,
        UsbDefaultMode.TETHERING,
        UsbDefaultMode.MIDI,
    )

    /**
     * Reads the current Default USB Configuration over the ADB shell (`dumpsys usb`). Returns null when
     * there is no live shell to read through (caller should fall back to [cached]); does NOT force a
     * connection, so it never causes WD/adbd churn. Caches the value on success. Call OFF the main thread.
     */
    fun readViaShell(context: Context): UsbDefaultMode? {
        val out = runShell(context, READ_CMD) ?: return null
        // Filter for the relevant line in Kotlin rather than a `| grep` (pipes are fragile over `shell:`).
        val line = out.lineSequence().firstOrNull { it.contains("screen_unlocked_functions") } ?: return null
        val mode = parse(line)
        if (mode != UsbDefaultMode.UNKNOWN) AppPreferences(context).setUsbDefaultMode(mode.name)
        AppLogger.i(TAG, "Default USB Configuration read: $mode (raw: '${line.trim()}')")
        return mode
    }

    /** The last successfully-read value (persisted), for UI shown while no shell is available. */
    fun cached(context: Context): UsbDefaultMode =
        runCatching { UsbDefaultMode.valueOf(AppPreferences(context).getUsbDefaultMode() ?: "") }
            .getOrDefault(UsbDefaultMode.UNKNOWN)

    /**
     * Sets the Default USB Configuration over the ADB shell (ensures a connection first). Returns true if
     * the command was delivered.
     *
     * NOTE: switching to [UsbDefaultMode.CHARGING] drops USB *data* (including USB-adb) until the user
     * picks a data mode again — harmless in normal wireless/loopback use, but it means a cable plugged
     * into a PC defaults to charging. Call OFF the main thread.
     */
    fun setViaShell(context: Context, mode: UsbDefaultMode): Boolean {
        if (mode == UsbDefaultMode.UNKNOWN) return false
        // `svc` applies the change ON-DEVICE even when its (empty) response stream closes early, so we
        // can't trust the stream result. Fire it, cache optimistically, then CONFIRM by reading back.
        runShell(context, "svc usb setScreenUnlockedFunctions ${mode.svcArg}".trimEnd())
        AppPreferences(context).setUsbDefaultMode(mode.name)
        val readback = runCatching { readViaShell(context) }.getOrNull()
        // A null read-back means the link dropped (expected when switching to CHARGING kills USB-adb) —
        // treat that as applied; only a read-back showing a DIFFERENT mode is a real failure.
        val ok = readback == null || readback == mode
        AppLogger.i(TAG, "Set Default USB Configuration to $mode (read-back=$readback, ok=$ok)")
        return ok
    }

    private fun parse(dumpsysLine: String): UsbDefaultMode {
        val v = dumpsysLine.substringAfter("screen_unlocked_functions=", "").trim().lowercase()
        return when {
            v.contains("mtp") -> UsbDefaultMode.FILE_TRANSFER
            v.contains("ptp") -> UsbDefaultMode.PTP
            v.contains("rndis") -> UsbDefaultMode.TETHERING
            v.contains("midi") -> UsbDefaultMode.MIDI
            v.isEmpty() || v == "none" -> UsbDefaultMode.CHARGING
            else -> UsbDefaultMode.UNKNOWN
        }
    }

    /**
     * Runs [cmd] over the embedded ADB shell and returns its stdout. The wireless/loopback link is
     * flaky ("Stream closed"), so retry once with a fresh connection — mirroring the daemon launcher.
     * Returns null if both attempts fail. Call OFF the main thread.
     */
    private fun runShell(context: Context, cmd: String): String? {
        repeat(2) { attempt ->
            val connected = if (attempt == 0) AdbShell.ensureConnected(context) else AdbShell.forceReconnect(context)
            if (!connected) return@repeat
            val out = runCatching {
                AdbShell.openShell(context, cmd).use { s -> s.openInputStream().use { String(it.readBytes()) } }
            }.onFailure { AppLogger.d(TAG, "USB shell cmd attempt ${attempt + 1} failed ('$cmd'): ${it.message}") }
                .getOrNull()
            if (out != null) return out
        }
        AppLogger.w(TAG, "USB shell cmd failed after retry ('$cmd')")
        return null
    }

    private const val READ_CMD = "dumpsys usb"
}
