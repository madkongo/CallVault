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
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.utils.AppLogger

/**
 * Enables/disables the opt-in "offline recording" (classic-tcpip loopback) transport, so the same
 * behaviour is shared by the Settings toggle and the post-update "What's new" note — one place that
 * sets the preference AND arms/disarms the loopback listener.
 *
 * The loopback port works OFF-WiFi (127.0.0.1 is always up), letting a call record with no network —
 * but arming it opens a local, RSA-gated debugging port, which is why it is opt-in behind a warning.
 * All methods do blocking ADB I/O — call OFF the main thread.
 */
object OfflineRecording {

    private const val TAG = "CV:OfflineRecording"

    /**
     * Turns offline recording ON: persists the opt-in and arms the loopback listener (needs Wi-Fi +
     * Wireless Debugging ONCE to arm; records off-WiFi thereafter until reboot). Re-warms the daemon so
     * the first off-WiFi call records. Returns true if the loopback listener is armed and reachable.
     */
    /**
     * Turns offline recording ON, and says how it went.
     *
     * The outcome is a [LoopbackArm] rather than a boolean so the screen can name the actual failure.
     * It used to print "connect to Wi-Fi once, then try from Settings" for every one of them.
     */
    fun enable(context: Context): LoopbackArm {
        // It cannot work without USB debugging: the listener lives inside adbd, and Android stops adbd off
        // Wi-Fi when USB debugging is off. Arming anyway would also restart adbd for nothing, which kills a
        // running Shizuku server (S1a, OP9, 2026-09-14).
        // Only a PROVEN off refuses. On a build that redacts the setting (Android 17) an unreadable
        // state used to read as off here, and off-Wi-Fi recording could never be switched on at all --
        // the user pressed the button and was told to enable something that was already enabled.
        if (AdbShell.usbDebuggingState(context).isOff) {
            AppLogger.i(TAG, "Not arming off-Wi-Fi recording: USB debugging is off")
            return LoopbackArm.NEEDS_USB_DEBUGGING
        }
        AppPreferences(context).setOfflineRecordingEnabled(true)
        // The user pressed the button, so Wireless debugging may be switched on for it even if they had
        // turned it off.
        val result = AdbShell.asUserRequest { AdbShell.armLoopbackIfNeededWithReason(context) }
        if (result == LoopbackArm.ARMED) {
            runCatching { RecorderBackend.ensureRunning(context) }
                .onFailure { AppLogger.w(TAG, "re-warm after enable failed: ${it.message}") }
        } else {
            AppLogger.i(TAG, "Could not arm loopback: $result")
        }
        return result
    }

    /** Turns offline recording OFF: clears the opt-in and closes the loopback port (best-effort). */
    fun disable(context: Context) {
        AppPreferences(context).setOfflineRecordingEnabled(false)
        // Under the lease, like arming: disarming needs the connection alive to send `usb:`, and
        // another ADB user finishing meanwhile could switch Wireless debugging off underneath it.
        runCatching { AdbShell.asAdbUser(context, "disabling offline recording") { AdbShell.disarmLoopback(context) } }
            .onFailure { AppLogger.d(TAG, "disarmLoopback on disable ignored: ${it.message}") }
    }
}
