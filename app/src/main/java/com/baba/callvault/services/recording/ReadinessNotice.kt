/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import androidx.annotation.StringRes
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.WifiState

/**
 * Which readiness notice the permanent notification shows.
 *
 * It used to have two states, ready and starting, so a recorder that could never come back said
 * "Call recorder starting up…" forever — #23, #24 and #39 all reported exactly that screen. "Starting"
 * promises it will finish, so it may only be shown while finishing is still possible.
 *
 * Standalone mode only: Shizuku mode does not run this service's readiness notice.
 */
enum class ReadinessNotice {
    READY,

    /** A relaunch that can still succeed. */
    STARTING,

    /** Both switches off and CallVault lacks the grant to switch Wireless debugging back on. */
    NO_DEBUGGING,

    /** USB debugging is off and there is no Wi-Fi, so Wireless debugging cannot run — adbd is down. */
    NEEDS_WIFI,

    /** USB debugging keeps adbd up, but nothing to dial without Wi-Fi: no armed off-Wi-Fi listener. */
    NEEDS_WIFI_TO_RESTART,

    /** Recovery keeps failing for a reason these switches do not explain. */
    STUCK;

    companion object {
        fun of(
            ready: Boolean,
            recoveryStuck: Boolean,
            usbDebuggingOn: Boolean,
            wirelessDebuggingOn: Boolean,
            wifi: WifiState,
            loopbackArmed: Boolean,
            hasGrant: Boolean,
        ): ReadinessNotice = when {
            ready -> READY
            // The named causes are evidence about the present, so they are shown at once rather than after
            // the failure streak, and they outrank the generic notice because they say what to do.
            // Only a positive "no Wi-Fi" counts; an unreadable state is not evidence.
            wifi == WifiState.NOT_CONNECTED && !usbDebuggingOn -> NEEDS_WIFI
            wifi == WifiState.NOT_CONNECTED && !loopbackArmed -> NEEDS_WIFI_TO_RESTART
            !usbDebuggingOn && !wirelessDebuggingOn && !hasGrant -> NO_DEBUGGING
            recoveryStuck -> STUCK
            else -> STARTING
        }
    }
}

/** Reads the switches and picks the strings, so the three places that post this notice cannot drift. */
object ReadinessNoticeText {

    fun current(context: Context, ready: Boolean): ReadinessNotice {
        // In Shizuku mode the debugging switches say nothing reliable — Sui needs neither — so only the
        // mode-independent notices apply there.
        val standalone = runCatching { !AppPreferences(context).getPrivilegedMode().needsShizuku }.getOrDefault(true)
        val stuck = DaemonKeepAliveService.isRecoveryStuck
        if (!standalone) {
            return when {
                ready -> ReadinessNotice.READY
                stuck -> ReadinessNotice.STUCK
                else -> ReadinessNotice.STARTING
            }
        }
        return ReadinessNotice.of(
            ready = ready,
            recoveryStuck = stuck,
            usbDebuggingOn = AdbShell.isUsbDebuggingEnabled(context),
            wirelessDebuggingOn = AdbShell.isWirelessDebuggingEnabled(context),
            wifi = WifiState.of(context),
            loopbackArmed = AdbShell.isLoopbackArmed(context),
            hasGrant = AdbShell.hasWriteSecureSettings(context),
        )
    }

    @StringRes
    fun title(notice: ReadinessNotice): Int = when (notice) {
        ReadinessNotice.READY -> R.string.notif_readiness_ready_title
        ReadinessNotice.STARTING -> R.string.notif_readiness_starting_title
        ReadinessNotice.NO_DEBUGGING,
        ReadinessNotice.NEEDS_WIFI,
        ReadinessNotice.NEEDS_WIFI_TO_RESTART,
        ReadinessNotice.STUCK -> R.string.notif_readiness_down_title
    }

    @StringRes
    fun text(notice: ReadinessNotice): Int = when (notice) {
        ReadinessNotice.READY -> R.string.notif_readiness_ready_text
        ReadinessNotice.STARTING -> R.string.notif_readiness_starting_text
        ReadinessNotice.NO_DEBUGGING -> R.string.notif_readiness_no_debugging_text
        ReadinessNotice.NEEDS_WIFI -> R.string.notif_readiness_needs_wifi_text
        ReadinessNotice.NEEDS_WIFI_TO_RESTART -> R.string.notif_readiness_needs_wifi_restart_text
        ReadinessNotice.STUCK -> R.string.notif_readiness_stuck_text
    }
}
