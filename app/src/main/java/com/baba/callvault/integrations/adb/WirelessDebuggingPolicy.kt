/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Whether Wireless debugging can be switched off once the daemon is running.
 *
 * CallVault's principle is "Wireless debugging only while it is needed" — but switching it off is not
 * always free. **`adbd` shuts down when its last transport is removed**, and the daemon is a child of an
 * `adbd` shell session, so turning off the *only* transport kills the daemon we just launched. Measured
 * on a Galaxy S24 FE: the daemon died 50-90 ms after each disable, the keep-alive relaunched it, the
 * relaunch re-enabled Wireless debugging, and the cycle repeated six times over two minutes with the app
 * never reaching "ready to record". The same code looked fine on a OnePlus only because USB debugging
 * was enabled there — a second transport that kept `adbd` alive and masked the bug entirely.
 *
 * So the rule is: **never remove `adbd`'s last transport while the daemon has to stay alive.** This
 * decision is kept free of `Context` so it can be tested exhaustively, which matters because every
 * branch here was a real device behaviour rather than a guess.
 */
enum class WirelessDebuggingPlan {
    /** USB debugging keeps `adbd` alive — safe to switch Wireless debugging off. */
    DROP_USB_KEEPS_ADBD,

    /**
     * Wireless debugging must stay on for the daemon to survive. The user is told, and pointed at the
     * setting that would let it be switched off.
     */
    KEEP_ONLY_TRANSPORT,
}

/** What [AdbShell.releaseWirelessDebugging] does with a Wireless debugging switch that is on. */
enum class WirelessDebuggingRelease(
    /** True when CallVault is choosing to leave it on, which the Connection settings show (#30). */
    val leavesItOn: Boolean,
) {
    SWITCH_OFF(false),

    /** "Never turn Wireless debugging off" is on. */
    LEAVE_SETTING_ON(true),

    /** The user switched it on, not us. */
    LEAVE_USERS_SWITCH(true),

    /** It is adbd's only transport; the notification already says so. */
    KEEP_ONLY_TRANSPORT(false),
}

object WirelessDebuggingPolicy {

    /**
     * Decides what to do with Wireless debugging now that the daemon is connected.
     *
     * **An armed loopback listener does NOT count.** That was assumed once and it was wrong: measured on
     * a Galaxy S24 FE running 1.4.8, with the loopback armed and USB debugging off, disabling Wireless
     * debugging still killed the daemon 57 ms later and the loop returned. `service.adb.tcp.port` is a
     * persisted *setting*, not a live transport — turning Wireless debugging off restarts `adbd` anyway,
     * the daemon dies with it, and the loopback only comes back ~12 s later, by which point the launcher
     * has given up and re-enabled Wireless debugging.
     *
     * Only USB debugging was ever actually proven to hold `adbd` up (toggling Wireless debugging on and
     * off left `adbd`'s pid unchanged and the daemon alive). The earlier belief that loopback did the
     * same came from a test run while USB debugging happened to be on — USB was doing the work.
     *
     * Restoring "Wireless debugging off" for loopback users needs a different shape: switch it off
     * BEFORE launching the daemon, wait for the loopback to return, and launch over that. See
     * `docs/dev-notes/backlog.md`.
     */
    fun plan(isUsbDebuggingEnabled: Boolean, isLoopbackArmed: Boolean): WirelessDebuggingPlan = when {
        isUsbDebuggingEnabled -> WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD
        else -> WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT
    }

    /** Whether Wireless debugging has to stay on — i.e. the user should be told why. */
    fun mustKeepWirelessDebugging(plan: WirelessDebuggingPlan): Boolean =
        plan == WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT

    /**
     * Whether CallVault may switch Wireless debugging off — which needs the switch to be **ours**.
     *
     * "Wireless debugging only while it is needed" was applied to the setting rather than to our own
     * use of it, so a switch the user flipped for their own reasons was taken away as soon as any ADB
     * operation of ours finished. mirror176 turns it on to reach his phone from a PC (#30) and watched
     * it flick off within a second, with a notification telling him to disable USB debugging instead —
     * advice that costs him the thing he wanted.
     *
     * So ownership decides, and the transport rule still overrides it: dropping `adbd`'s last transport
     * kills the daemon whoever turned the switch on.
     */
    fun mayRelease(plan: WirelessDebuggingPlan, weEnabledIt: Boolean): Boolean =
        weEnabledIt && !mustKeepWirelessDebugging(plan)

    /**
     * The one decision [AdbShell.releaseWirelessDebugging] acts on.
     *
     * [neverTurnOff] is the user's "Never turn Wireless debugging off" setting (#30 follow-up): with it on,
     * nothing is switched off, whoever turned it on. With it off, this is [mayRelease] as before.
     */
    fun releaseDecision(
        plan: WirelessDebuggingPlan,
        weEnabledIt: Boolean,
        neverTurnOff: Boolean,
    ): WirelessDebuggingRelease = when {
        neverTurnOff -> WirelessDebuggingRelease.LEAVE_SETTING_ON
        !weEnabledIt -> WirelessDebuggingRelease.LEAVE_USERS_SWITCH
        mustKeepWirelessDebugging(plan) -> WirelessDebuggingRelease.KEEP_ONLY_TRANSPORT
        else -> WirelessDebuggingRelease.SWITCH_OFF
    }
}
