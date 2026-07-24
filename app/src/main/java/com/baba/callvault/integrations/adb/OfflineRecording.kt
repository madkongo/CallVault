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
import com.baba.callvault.server.RecorderServerLauncher
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
    fun enable(context: Context): Boolean {
        AppPreferences(context).setOfflineRecordingEnabled(true)
        val armed = AdbShell.armLoopbackIfNeeded(context)
        if (armed) {
            runCatching { RecorderServerLauncher.ensureServerRunning(context) }
                .onFailure { AppLogger.w(TAG, "re-warm after enable failed: ${it.message}") }
        } else {
            AppLogger.i(TAG, "Could not arm loopback (needs Wi-Fi + Wireless Debugging once)")
        }
        return armed
    }

    /** Turns offline recording OFF: clears the opt-in and closes the loopback port (best-effort). */
    fun disable(context: Context) {
        AppPreferences(context).setOfflineRecordingEnabled(false)
        runCatching { AdbShell.disarmLoopback(context) }
            .onFailure { AppLogger.d(TAG, "disarmLoopback on disable ignored: ${it.message}") }
    }
}
