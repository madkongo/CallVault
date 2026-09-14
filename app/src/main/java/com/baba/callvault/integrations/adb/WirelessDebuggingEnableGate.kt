/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** What [AdbShell.enableWirelessDebugging] should do. */
enum class WirelessDebuggingEnable {
    /** Already on — it is the user's switch, not ours. */
    ALREADY_ON,

    /** No WRITE_SECURE_SETTINGS; the write would throw. */
    NO_GRANT,

    /**
     * Not on Wi-Fi. The framework would write the setting straight back to 0, so the attempt achieves
     * nothing — and on OxygenOS 16 (#24) and One UI 7 (#39) it also switched USB debugging on and
     * restarted adbd, taking any Shizuku server with it.
     */
    NO_WIFI,

    /** Make the write. */
    WRITE,
}

/**
 * Decides whether CallVault may switch Wireless debugging on. Free of `Context` so every branch is tested.
 *
 * Source for [WirelessDebuggingEnable.NO_WIFI]: `AdbDebuggingManager` (android15-release), handling
 * `MSG_ADBDWIFI_ENABLE` — `getCurrentWifiApInfo()` returns null off Wi-Fi and the handler puts
 * `ADB_WIFI_ENABLED` back to 0.
 */
object WirelessDebuggingEnableGate {

    fun decide(alreadyOn: Boolean, hasGrant: Boolean, wifi: WifiState): WirelessDebuggingEnable = when {
        alreadyOn -> WirelessDebuggingEnable.ALREADY_ON
        !hasGrant -> WirelessDebuggingEnable.NO_GRANT
        // Only a positive "not connected" blocks. Unknown carries on, because blocking a write that
        // would have worked is exactly the kind of dead end this exists to remove.
        wifi == WifiState.NOT_CONNECTED -> WirelessDebuggingEnable.NO_WIFI
        else -> WirelessDebuggingEnable.WRITE
    }
}
