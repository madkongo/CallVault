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

    @Test
    fun `a switch the user turned off stays off by default`() {
        assertEquals(
            WirelessDebuggingEnable.RESPECT_USER,
            WirelessDebuggingEnableGate.decide(alreadyOn = false, hasGrant = true, wifi = WifiState.CONNECTED, userTurnedOff = true),
        )
    }

    @Test
    fun `the opt-in setting lets CallVault turn it back on`() {
        assertEquals(
            WirelessDebuggingEnable.WRITE,
            WirelessDebuggingEnableGate.decide(alreadyOn = false, hasGrant = true, wifi = WifiState.CONNECTED, userTurnedOff = true, enforced = true),
        )
    }

    @Test
    fun `a button the user pressed is the user asking`() {
        assertEquals(
            WirelessDebuggingEnable.WRITE,
            WirelessDebuggingEnableGate.decide(alreadyOn = false, hasGrant = true, wifi = WifiState.CONNECTED, userTurnedOff = true, userRequested = true),
        )
    }

    // ---- Borrowing the switch back to re-arm the off-Wi-Fi listener (the 2026-09-19 reboot deadlock) ----

    @Test
    fun `a switch the user turned off may still be borrowed when nothing else can re-arm the listener`() {
        // The reboot case: the listener is gone, off-Wi-Fi recording is on, and this one write is the
        // only way to get it back. The switch is handed straight back afterwards, so the user's setting
        // survives the borrow. Without this the phone never records again -- measured on the OP12.
        assertEquals(
            WirelessDebuggingEnable.WRITE,
            WirelessDebuggingEnableGate.decide(
                alreadyOn = false,
                hasGrant = true,
                wifi = WifiState.CONNECTED,
                userTurnedOff = true,
                borrowingForLoopback = true,
            ),
        )
    }

    @Test
    fun `borrowing never overrides the reasons the write could not work anyway`() {
        // Borrowing answers "may we?", not "would it help?". Off Wi-Fi the framework writes it straight
        // back to 0, and with no grant the write throws -- neither becomes possible because we need it.
        assertEquals(
            WirelessDebuggingEnable.NO_WIFI,
            WirelessDebuggingEnableGate.decide(
                alreadyOn = false,
                hasGrant = true,
                wifi = WifiState.NOT_CONNECTED,
                userTurnedOff = true,
                borrowingForLoopback = true,
            ),
        )
        assertEquals(
            WirelessDebuggingEnable.NO_GRANT,
            WirelessDebuggingEnableGate.decide(
                alreadyOn = false,
                hasGrant = false,
                wifi = WifiState.CONNECTED,
                userTurnedOff = true,
                borrowingForLoopback = true,
            ),
        )
    }

    @Test
    fun `not borrowing leaves the user's switch respected exactly as before`() {
        // The default is unchanged, so every caller that does not opt in keeps 2026-09-14's behaviour.
        assertEquals(
            WirelessDebuggingEnable.RESPECT_USER,
            WirelessDebuggingEnableGate.decide(
                alreadyOn = false,
                hasGrant = true,
                wifi = WifiState.CONNECTED,
                userTurnedOff = true,
                borrowingForLoopback = false,
            ),
        )
    }
}
