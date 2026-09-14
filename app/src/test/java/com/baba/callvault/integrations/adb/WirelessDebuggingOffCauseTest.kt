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
    fun `anything else is the user`() {
        assertEquals(WirelessDebuggingOffCause.USER, WirelessDebuggingOffCause.of(weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.CONNECTED))
        assertEquals(WirelessDebuggingOffCause.USER, WirelessDebuggingOffCause.of(weJustTurnedItOff = false, weJustTurnedItOn = false, wifi = WifiState.UNKNOWN))
    }
}
