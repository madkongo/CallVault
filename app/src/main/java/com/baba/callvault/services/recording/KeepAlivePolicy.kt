/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.PrivilegedMode

/**
 * When [DaemonKeepAliveService] runs, and what it may do.
 *
 * It runs in EVERY mode and app-calls setting (owner decision 2026-11-20, review P1a). Without a running foreground
 * service Android's unused-app hibernation stops the app and revokes its phone and call-log permissions, so calls
 * are silently not recorded (R4/Q54), and an answered call can hit the background foreground-service start refusal
 * (N26/Q53). What it DOES differs by mode:
 *  - standalone: keeps OUR daemon alive over our own ADB, and hosts [VoipCallDetector];
 *  - Shizuku: the process anchor and status notice, asks Shizuku for its recorder again when it is down, and hosts
 *    app-call detection when app calls are on. Never touches our ADB (see [mayUseAdb]).
 * One rule, read by every place that starts or stops the service, so they cannot disagree.
 */
object KeepAlivePolicy {

    /** Everything the keep-alive may do to our own embedded ADB. Standalone: all of it. Shizuku: none of it. */
    enum class AdbAction {
        WATCH_SWITCHES, REVIVE_ADBD, ENABLE_WIRELESS_DEBUGGING, RESTORE_USB_DEBUGGING, DROP_ADB_CONNECTION, APPROVE_WIFI,
    }

    fun allowedAdbActions(mode: PrivilegedMode): Set<AdbAction> =
        if (mayUseAdb(mode)) AdbAction.entries.toSet() else emptySet()

    /** What to do with the ADB watchers (debugging-switch observers, screen receiver, Wi-Fi callback) after a mode change. */
    enum class WatcherAction { NONE, REGISTER, UNREGISTER }

    /**
     * The watchers are registered once, in onCreate, for the mode of that moment, and this service outlives a mode
     * switch (a start() between the switch's stop and the preference write builds an instance for the OLD mode).
     * So the registered state is compared with what the mode allows now, at every start and every tick.
     */
    fun reconcileWatchers(registered: Boolean, mayUseAdb: Boolean): WatcherAction = when {
        registered && !mayUseAdb -> WatcherAction.UNREGISTER
        !registered && mayUseAdb -> WatcherAction.REGISTER
        else -> WatcherAction.NONE
    }

    enum class Role { DAEMON_WATCHDOG, SHIZUKU_ANCHOR }

    data class Decision(
        val run: Boolean,
        val role: Role,
        val mayUseAdb: Boolean,
        val adbActions: Set<AdbAction>,
        val reason: String,
    )

    fun decision(mode: PrivilegedMode, voipEnabled: Boolean): Decision {
        val role = if (mode.needsShizuku) Role.SHIZUKU_ANCHOR else Role.DAEMON_WATCHDOG
        val what = when (role) {
            Role.DAEMON_WATCHDOG -> "keeps our daemon alive over ADB"
            Role.SHIZUKU_ANCHOR -> "process anchor + status notice, no ADB daemon"
        }
        return Decision(
            run = true,
            role = role,
            mayUseAdb = mayUseAdb(mode),
            adbActions = allowedAdbActions(mode),
            reason = "mode=$mode appCalls=$voipEnabled role=$role: $what",
        )
    }

    /**
     * Whether Application.onCreate starts the keep-alive. Shizuku: always (as before). Standalone: once the transport is
     * set up, so a process Android revived after a force-stop (only to re-bind a listener) runs normally again and its
     * daemon's orphan watch finds an app that can settle the call (owner decision 2026-11-22). The user's "recording
     * stopped" switch is honoured inside the start itself.
     */
    fun shouldStartAtAppStart(mode: PrivilegedMode, transportSetUp: Boolean): Boolean = mode.needsShizuku || transportSetUp

    fun shouldRun(mode: PrivilegedMode, voipEnabled: Boolean): Boolean = decision(mode, voipEnabled).run

    /**
     * Whether recovery may touch our embedded ADB — Wireless debugging, adbd, the loopback. Never in
     * Shizuku mode: its owner chose Shizuku so that none of that would happen, and a keep-alive that
     * relaunched our ADB daemon there is how a wrong host once served Shizuku mode on the OP9.
     */
    fun mayUseAdb(mode: PrivilegedMode): Boolean = !mode.needsShizuku
}

/** Remembers the last decision so the always-on log gets one line per change, not one per start. */
class KeepAliveDecisionLog {
    private var last: String? = null

    @Synchronized
    fun lineIfChanged(decision: KeepAlivePolicy.Decision): String? {
        if (decision.reason == last) return null
        last = decision.reason
        return "keep-alive runs: ${decision.reason}"
    }
}
