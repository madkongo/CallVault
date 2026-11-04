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
import android.os.PowerManager

/**
 * An always-on timeline of what the recorder depends on, one line per change — written whether or not debug logging
 * is on, into the same file as [UsbDebuggingEvents] (and so into every debug report).
 *
 * Why: on the Honor BVL-N59 (2026-11-04) a call at 15:41 was missed and the report could not say why — debug logging
 * was off, and the phone's own system log held nothing newer than its last boot. This records, as it happens: the
 * recorder alive or not, adbd, USB debugging (as the recorder reads it), Wireless debugging, Wi-Fi, the screen and the
 * lock, the off-Wi-Fi listener; and each relaunch attempt with its result. Only changes are written, so a quiet day
 * costs a handful of lines.
 */
object TransportTimeline {

    @Volatile private var last: String? = null

    /** Writes the current state if it differs from the last one written. [why] says what prompted the look. */
    fun snapshot(context: Context, recorderAlive: Boolean, why: String) {
        val state = stateOf(context, recorderAlive)
        if (state == last) return
        last = state
        UsbDebuggingEvents.record(context, "state ($why): $state")
    }

    /** A one-off event: a death, a relaunch and its outcome, a decision not to try. */
    fun event(context: Context, text: String) = UsbDebuggingEvents.record(context, "event: $text")

    private fun stateOf(context: Context, alive: Boolean): String {
        val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        val keyguard = runCatching { context.getSystemService(KeyguardManager::class.java) }.getOrNull()
        val screen = when (runCatching { power?.isInteractive }.getOrNull()) {
            true -> "on"
            false -> "off"
            null -> "?"
        }
        val locked = runCatching { keyguard?.isKeyguardLocked }.getOrNull()
        val usbShell = when (UsbDebuggingShellRead.current()) {
            true -> "on"
            false -> "off"
            null -> "?"
        }
        return buildString {
            append("recorder=").append(if (alive) "alive" else "DOWN")
            append(" adbd=").append(AdbShell.adbdState())
            append(" usb=").append(AdbShell.usbDebuggingState(context)).append("(shell:").append(usbShell).append(')')
            append(" wd=").append(if (AdbShell.isWirelessDebuggingEnabled(context)) "on" else "off")
            append(" wifi=").append(WifiState.of(context))
            append(" offlinePort=").append(if (runCatching { AdbShell.isLoopbackArmed(context) }.getOrDefault(false)) "armed" else "none")
            append(" screen=").append(screen).append(if (locked == true) "+locked" else "")
            append(" chargeOnlyAdb=").append(UsbOffSnapshot.chargingAdb(context) ?: "absent")
        }
    }
}
