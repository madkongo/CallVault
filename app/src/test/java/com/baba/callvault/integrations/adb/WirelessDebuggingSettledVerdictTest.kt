/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/** C99: the "switched off by hand" verdict is given after a settle window, not at the instant of the 1 to 0 change. */
class WirelessDebuggingSettledVerdictTest {

    @Test
    fun `Wi-Fi stable on the same network is the user's`() {
        assertEquals(
            WirelessDebuggingSettledVerdict.USER,
            WirelessDebuggingSettledVerdict.of("net1|aa:bb", "net1|aa:bb", WifiState.CONNECTED, wifiLostInWindow = false),
        )
    }

    @Test
    fun `Wi-Fi lost inside the window is Android's even if it reads connected again`() {
        assertEquals(
            WirelessDebuggingSettledVerdict.ANDROID_WIFI_DROPPED,
            WirelessDebuggingSettledVerdict.of("net1|aa:bb", "net1|aa:bb", WifiState.CONNECTED, wifiLostInWindow = true),
        )
    }

    @Test
    fun `Wi-Fi gone when the window ends is Android's`() {
        assertEquals(
            WirelessDebuggingSettledVerdict.ANDROID_WIFI_DROPPED,
            WirelessDebuggingSettledVerdict.of("net1|aa:bb", null, WifiState.NOT_CONNECTED, wifiLostInWindow = false),
        )
    }

    @Test
    fun `a different network or access point is Android's`() {
        assertEquals(
            WirelessDebuggingSettledVerdict.ANDROID_NETWORK_CHANGED,
            WirelessDebuggingSettledVerdict.of("net1|aa:bb", "net1|cc:dd", WifiState.CONNECTED, wifiLostInWindow = false),
        )
        assertEquals(
            WirelessDebuggingSettledVerdict.ANDROID_NETWORK_CHANGED,
            WirelessDebuggingSettledVerdict.of("net1|aa:bb", "net2|aa:bb", WifiState.CONNECTED, wifiLostInWindow = false),
        )
    }

    @Test
    fun `an unreadable network identity never blocks the user's verdict`() {
        assertEquals(
            WirelessDebuggingSettledVerdict.USER,
            WirelessDebuggingSettledVerdict.of(null, null, WifiState.UNKNOWN, wifiLostInWindow = false),
        )
        assertEquals(
            WirelessDebuggingSettledVerdict.USER,
            WirelessDebuggingSettledVerdict.of(null, "net1|aa:bb", WifiState.CONNECTED, wifiLostInWindow = false),
        )
    }

    @Test
    fun `our own off write is never the user's, whatever the window shows`() {
        // The initial classifier already returns OURS and never reaches the settle window.
        assertEquals(
            WirelessDebuggingOffCause.OURS,
            WirelessDebuggingOffCause.of(weJustTurnedItOff = true, weJustTurnedItOn = false, wifi = WifiState.CONNECTED),
        )
    }
}
