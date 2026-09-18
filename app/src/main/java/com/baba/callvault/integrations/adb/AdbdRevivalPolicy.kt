/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** Android's own report of whether adbd is running — `init.svc.adbd`, which any app may read. */
enum class AdbdState {
    RUNNING,
    STOPPED,

    /** Unreadable, empty, or in between (`restarting`). Never treated as "down". */
    UNKNOWN;

    companion object {
        fun of(value: String?): AdbdState = when (value?.trim()) {
            "running" -> RUNNING
            "stopped" -> STOPPED
            else -> UNKNOWN
        }
    }
}

/** What CallVault may do about a stopped adbd. */
enum class AdbdRevival {
    NOTHING,

    /** Both switches off: switching Wireless debugging on starts adbd. */
    ENABLE_WIRELESS_DEBUGGING,

    /**
     * Wireless debugging reads on but adbd is stopped — what turning USB debugging off leaves behind. Only a
     * change of the switch makes AdbService start adbd again, so it is switched off and back on.
     */
    CYCLE_WIRELESS_DEBUGGING,

    /** No Wi-Fi, so Wireless debugging cannot run; writing it would be undone. */
    NEEDS_WIFI,

    /** No WRITE_SECURE_SETTINGS; only the user can change the switches. */
    NO_GRANT,
}

/**
 * Decides how to bring adbd back. Free of `Context` so every branch is tested.
 *
 * Why it exists, measured 2026-09-14 (docs/dev-notes/2026-09-14-debugging-switches-model.md):
 * stock `init.usb.configfs.rc` has `on property:sys.usb.config=none … stop adbd`, so turning USB debugging
 * off stops adbd **even with Wireless debugging on**. AdbService does not restart it, because from its
 * point of view Wireless debugging never changed. On the OP9, switching Wireless debugging off and on
 * brought adbd back and left USB debugging off.
 */
object AdbdRevivalPolicy {

    fun decide(
        adbd: AdbdState,
        usbDebuggingOn: Boolean,
        wirelessDebuggingOn: Boolean,
        wifi: WifiState,
        hasGrant: Boolean,
        /**
         * Whether switching Wireless debugging ON from off is allowed here. False in Shizuku mode (CallVault
         * does not own the switches there) and when the user turned the switch off themselves. A cycle is
         * still allowed: it ends with the switch exactly as it was.
         */
        mayEnable: Boolean = true,
        /**
         * Whether a Shizuku server answers right now. A Shizuku server is a shell-uid process hosted by
         * `adbd` and dies with it, so one that answers is proof `adbd` is up — whatever `init.svc.adbd`
         * said a moment ago.
         */
        shizukuServerRunning: Boolean = false,
    ): AdbdRevival = when {
        adbd != AdbdState.STOPPED -> AdbdRevival.NOTHING
        // Two readings that contradict each other, and this one is the reliable half: a binder that
        // answers cannot belong to a process whose cgroup has been killed, while `init.svc.adbd` is a
        // property that lags a restart. Cycling on the stale reading would restart a perfectly live adbd
        // and kill the Shizuku server with it — #39's "when it starts it disables automatically within a
        // second and wireless debugging seems to restart". Nothing is lost by waiting: the caller looks
        // again on its next tick, and with adbd actually up the connection is what needs retrying, not
        // the switches.
        shizukuServerRunning -> AdbdRevival.NOTHING
        // With USB debugging on, init starts adbd itself; a stopped reading is a moment in a restart.
        usbDebuggingOn -> AdbdRevival.NOTHING
        wifi == WifiState.NOT_CONNECTED -> AdbdRevival.NEEDS_WIFI
        !hasGrant -> AdbdRevival.NO_GRANT
        wirelessDebuggingOn -> AdbdRevival.CYCLE_WIRELESS_DEBUGGING
        !mayEnable -> AdbdRevival.NOTHING
        else -> AdbdRevival.ENABLE_WIRELESS_DEBUGGING
    }
}
