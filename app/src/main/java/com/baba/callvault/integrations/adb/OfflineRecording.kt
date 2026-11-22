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
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.SharedStatusNotice
import com.baba.callvault.utils.AppLogger

/**
 * Enables/disables the opt-in "offline recording" (classic-tcpip loopback) transport, so the same
 * behaviour is shared by the Settings toggle and the post-update "What's new" note — one place that
 * sets the preference AND arms/disarms the loopback listener.
 *
 * The loopback port works OFF-WiFi (127.0.0.1 is always up), letting a call record with no network —
 * but arming it opens a debugging port that listens on every network the phone joins until the next reboot
 * (gated only by the phone's debugging key), which is why it is opt-in behind a warning.
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
        // Arming restarts adbd, and a restart ends the call being recorded (C35). Only this button asks here;
        // the recorder launcher re-arms through AdbShell directly and is never refused.
        if (!OfflineToggleGuard.mayRestartAdbd(isRecordingLive())) {
            TransportTimeline.event(context, "off-Wi-Fi recording not switched on: a call is being recorded (arming restarts adbd)")
            AppLogger.i(TAG, "Not arming off-Wi-Fi recording: a call is being recorded")
            return LoopbackArm.BUSY_RECORDING
        }
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
        // Set BEFORE arming (arming borrows Wireless debugging only while it is on), and put back when the
        // listener did not come up, so the stored opt-in and the toggle agree (C56).
        val prefs = AppPreferences(context)
        val result = enableWithOptIn(
            wasOn = prefs.isOfflineRecordingEnabled(),
            setOptIn = { prefs.setOfflineRecordingEnabled(it) },
            arm = {
                // The user pressed the button, so Wireless debugging may be switched on for it even if they
                // had turned it off.
                AdbShell.asUserRequest { AdbShell.armLoopbackIfNeededWithReason(context) }
            },
        )
        if (result == LoopbackArm.ARMED) {
            runCatching { RecorderBackend.ensureRunning(context) }
                .onFailure { AppLogger.w(TAG, "re-warm after enable failed: ${it.message}") }
        } else {
            AppLogger.i(TAG, "Could not arm loopback: $result")
        }
        return result
    }

    /**
     * Turns offline recording OFF: clears the opt-in and closes the loopback port (best-effort).
     *
     * Returns true when nothing was left open. False means a call is being recorded AND the listener CallVault armed is
     * open: closing it restarts adbd and would end the call (C35), and nothing closes it afterwards, so it stays until
     * the phone restarts or the user switches this on and off again once the call is over (UI-2). The screen says so.
     */
    fun disable(context: Context): Boolean {
        val mayDisarm = OfflineToggleGuard.mayRestartAdbd(isRecordingLive())
        val listenerOpen = !mayDisarm && AdbShell.isOwnLoopbackListenerOpen(context)
        disableInOrder(
            mayDisarm = mayDisarm,
            disarm = {
                // Under the lease, like arming: disarming needs the connection alive to send `usb:`.
                runCatching { AdbShell.asAdbUser(context, "disabling offline recording") { AdbShell.disarmLoopback(context) } }
                    .onFailure { AppLogger.w(TAG, "disarmLoopback on disable failed: ${it.message}") }
            },
            skipped = {
                // Closing the listener restarts adbd and would end the call being recorded (C35).
                TransportTimeline.event(context, "off-Wi-Fi listener left open: a call is being recorded (it closes on reboot, or when this is switched on and off again after the call); listener open=$listenerOpen")
                AppLogger.i(TAG, "Not closing the off-Wi-Fi listener: a call is being recorded (listener open=$listenerOpen)")
            },
            clearOptIn = { AppPreferences(context).setOfflineRecordingEnabled(false) },
        )
        return !isLeftOpen(mayDisarm, listenerOpen)
    }

    /** UI-2: the listener stays open when the disarm was skipped for a call and a listener of ours is really open. */
    internal fun isLeftOpen(mayDisarm: Boolean, ownListenerOpen: Boolean): Boolean = !mayDisarm && ownListenerOpen

    /** The C56 ordering for enabling, free of Android so it is tested: opt-in on, arm, put back unless armed. */
    internal fun enableWithOptIn(wasOn: Boolean, setOptIn: (Boolean) -> Unit, arm: () -> LoopbackArm): LoopbackArm {
        setOptIn(true)
        val result = try {
            arm()
        } catch (e: Exception) {
            setOptIn(OfflineToggleGuard.optInAfterEnable(wasOn, LoopbackArm.NO_ADB_SERVICE))
            AppLogger.w(TAG, "Arming off-Wi-Fi recording threw; opt-in put back: ${e.message}")
            throw e
        }
        setOptIn(OfflineToggleGuard.optInAfterEnable(wasOn, result))
        return result
    }

    /**
     * The C56 ordering for disabling: disarm (or skip) FIRST, and clear the opt-in whatever happens.
     * True when the disarm was attempted, false when it was skipped and the listener stays open.
     */
    internal fun disableInOrder(mayDisarm: Boolean, disarm: () -> Unit, skipped: () -> Unit, clearOptIn: () -> Unit): Boolean {
        try {
            if (mayDisarm) disarm() else skipped()
        } finally {
            clearOptIn()
        }
        return mayDisarm
    }

    /** A call is being recorded: the daemon says so, or a recording owns the shared notification (VoIP or carrier). */
    private fun isRecordingLive(): Boolean =
        runCatching { RecorderConnection.service?.isRecording == true }.getOrDefault(false) ||
            SharedStatusNotice.isHeldByRecording
}
