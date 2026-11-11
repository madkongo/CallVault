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

/**
 * Who switched Wireless debugging off. Only the user's own change may make CallVault hold back.
 *
 * Measured 2026-09-14: Android writes the switch to 0 itself when it refuses our write on an untrusted
 * network, and AOSP does the same when Wi-Fi drops. Neither is the user's choice, so neither may block
 * CallVault from switching it back on later.
 */
class WirelessDebuggingOffCauseTest {

    @Test
    fun `our own write is ours`() {
        assertEquals(WirelessDebuggingOffCause.OURS, WirelessDebuggingOffCause.of(weJustTurnedItOff = true, weJustTurnedItOn = false, wifi = WifiState.CONNECTED))
    }

    @Test
    fun `off right after we turned it on is Android refusing, not the user`() {
        assertEquals(WirelessDebuggingOffCause.ANDROID_REFUSED, WirelessDebuggingOffCause.of(weJustTurnedItOff = false, weJustTurnedItOn = true, wifi = WifiState.CONNECTED))
    }

    @Test
    fun `off with no wifi is Android clearing it`() {
        assertEquals(WirelessDebuggingOffCause.ANDROID_NO_WIFI, WirelessDebuggingOffCause.of(weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `off as the phone leaves a Wi-Fi network is Android, even when the next network is already up`() {
        // OP9, 2026-10-21: moving from the home Wi-Fi straight to a hotspot. Android logged "Network
        // disconnected. Disabling adbwifi." while Wi-Fi still read connected (the hotspot), and this was taken
        // for the user's tap: CallVault then refused to switch it back on for the rest of the day.
        assertEquals(
            WirelessDebuggingOffCause.ANDROID_NETWORK_CHANGED,
            WirelessDebuggingOffCause.of(
                weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.CONNECTED, wifiJustLost = true,
            ),
        )
    }

    @Test
    fun `off just after we restarted adbd is another app reacting, not the user`() {
        // A tester, POCO X7 Pro, 2026-10-22: 1.2-1.5 s after each of our tcpip arms the switch went off, every time.
        // Shizuku's watchdog restarting Shizuku is the likely writer. Taken for the user, CallVault stood down.
        assertEquals(
            WirelessDebuggingOffCause.AFTER_OUR_ADBD_RESTART,
            WirelessDebuggingOffCause.of(
                weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.CONNECTED, weJustRestartedAdbd = true,
            ),
        )
    }

    @Test
    fun `Android's own causes still win over our adbd restart`() {
        assertEquals(
            WirelessDebuggingOffCause.ANDROID_NO_WIFI,
            WirelessDebuggingOffCause.of(
                weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.NOT_CONNECTED, weJustRestartedAdbd = true,
            ),
        )
    }

    @Test
    fun `anything else is the user`() {
        assertEquals(WirelessDebuggingOffCause.USER, WirelessDebuggingOffCause.of(weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.CONNECTED))
        assertEquals(WirelessDebuggingOffCause.USER, WirelessDebuggingOffCause.of(weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.UNKNOWN))
    }
}
