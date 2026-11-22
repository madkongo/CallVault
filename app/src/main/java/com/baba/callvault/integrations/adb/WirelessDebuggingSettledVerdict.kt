/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * C99 (OP9): Android switched Wireless debugging off on a Wi-Fi flap, and for a few ms the app still read Wi-Fi as
 * connected, so it took the switch-off for the user's. The verdict is now given after a short settle window and
 * is [USER] only if Wi-Fi never dropped and is still the same network.
 *
 * Network identity is a string the caller builds (network handle plus BSSID when readable); null means unreadable,
 * and an unreadable identity never blocks [USER], so no phone maker is locked out of the real user case.
 */
enum class WirelessDebuggingSettledVerdict {
    USER,
    ANDROID_WIFI_DROPPED,
    ANDROID_NETWORK_CHANGED;

    companion object {
        /** About the measured gap between Android's write and Wi-Fi being reported gone (0.18 s), with margin. */
        const val SETTLE_WINDOW_MS = 2_500L

        fun of(
            identityAtOff: String?,
            identityNow: String?,
            wifiNow: WifiState,
            wifiLostInWindow: Boolean,
        ): WirelessDebuggingSettledVerdict = when {
            wifiLostInWindow || wifiNow == WifiState.NOT_CONNECTED -> ANDROID_WIFI_DROPPED
            identityAtOff != null && identityNow != null && identityAtOff != identityNow -> ANDROID_NETWORK_CHANGED
            else -> USER
        }
    }
}
