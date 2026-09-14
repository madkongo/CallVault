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
 * When CallVault may write adb_wifi_enabled=1.
 *
 * AOSP's AdbDebuggingManager writes the setting straight back to 0 when the phone is not on Wi-Fi, so the
 * write never helps there — and on OxygenOS 16 (#24) and One UI 7 (#39) the attempt also switched the
 * user's USB debugging on and restarted adbd, which killed Shizuku. The gate must never block a write
 * that could have worked, so an unknown Wi-Fi state falls through to the write.
 */
class WirelessDebuggingEnableGateTest {

    @Test
    fun `already on is left alone`() {
        assertEquals(
            WirelessDebuggingEnable.ALREADY_ON,
            WirelessDebuggingEnableGate.decide(alreadyOn = true, hasGrant = true, wifi = WifiState.NOT_CONNECTED),
        )
    }

    @Test
    fun `no grant means no write`() {
        assertEquals(
            WirelessDebuggingEnable.NO_GRANT,
            WirelessDebuggingEnableGate.decide(alreadyOn = false, hasGrant = false, wifi = WifiState.CONNECTED),
        )
    }

    @Test
    fun `off wifi the framework undoes the write, so it is not made`() {
        assertEquals(
            WirelessDebuggingEnable.NO_WIFI,
            WirelessDebuggingEnableGate.decide(alreadyOn = false, hasGrant = true, wifi = WifiState.NOT_CONNECTED),
        )
    }

    @Test
    fun `on wifi the write is made`() {
        assertEquals(
            WirelessDebuggingEnable.WRITE,
            WirelessDebuggingEnableGate.decide(alreadyOn = false, hasGrant = true, wifi = WifiState.CONNECTED),
        )
    }

    @Test
    fun `an unknown wifi state never blocks a write that might have worked`() {
        assertEquals(
            WirelessDebuggingEnable.WRITE,
            WirelessDebuggingEnableGate.decide(alreadyOn = false, hasGrant = true, wifi = WifiState.UNKNOWN),
        )
    }
}
