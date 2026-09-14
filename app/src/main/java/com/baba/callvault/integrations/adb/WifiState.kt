/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the phone is associated with a Wi-Fi network — the one thing Wireless debugging needs.
 *
 * AOSP's `AdbDebuggingManager` refuses to start Wireless debugging, and writes `adb_wifi_enabled` back
 * to 0, when there is no Wi-Fi connection; it clears it again when Wi-Fi drops. So this is a statement
 * about whether Wireless debugging *can* work, not about internet.
 *
 * [UNKNOWN] exists so a failure to read is never mistaken for "no Wi-Fi": every caller treats it as
 * "might work" and carries on as it did before this check existed.
 */
enum class WifiState {
    CONNECTED,
    NOT_CONNECTED,
    UNKNOWN;

    companion object {
        /**
         * Looks at every network, not just the default one. A Wi-Fi network without internet is not the
         * default while mobile data is up, and Wireless debugging still works on it.
         */
        @Suppress("DEPRECATION") // allNetworks: the callback API cannot answer "right now" synchronously.
        fun of(context: Context): WifiState = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return UNKNOWN
            val onWifi = cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (onWifi) CONNECTED else NOT_CONNECTED
        }.getOrDefault(UNKNOWN)
    }
}
