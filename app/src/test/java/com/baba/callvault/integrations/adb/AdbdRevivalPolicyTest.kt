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
 * What CallVault may do when adbd is not running.
 *
 * Measured 2026-09-14 (docs/dev-notes/2026-09-14-debugging-switches-model.md): turning USB debugging off
 * stops adbd even with Wireless debugging on (stock init.usb.configfs.rc), and nothing restarts it. On
 * the OP9, switching Wireless debugging off and on brought it back and left USB debugging off.
 */
class AdbdRevivalPolicyTest {

    private fun decide(
        adbd: AdbdState = AdbdState.STOPPED,
        usbOn: Boolean = false,
        wdOn: Boolean = false,
        wifi: WifiState = WifiState.CONNECTED,
        hasGrant: Boolean = true,
        mayEnable: Boolean = true,
        shizukuServerRunning: Boolean = false,
    ) = AdbdRevivalPolicy.decide(adbd, usbOn, wdOn, wifi, hasGrant, mayEnable, shizukuServerRunning)

    @Test
    fun `a running adbd is left alone`() {
        assertEquals(AdbdRevival.NOTHING, decide(adbd = AdbdState.RUNNING, wdOn = true))
    }

    @Test
    fun `an unreadable state is not evidence that adbd is down`() {
        assertEquals(AdbdRevival.NOTHING, decide(adbd = AdbdState.UNKNOWN, wdOn = true))
    }

    @Test
    fun `with usb debugging on, adbd is init's to start`() {
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true))
    }

    @Test
    fun `wireless debugging on but adbd stopped is the usb-off case, and cycling it is the cure`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true))
    }

    @Test
    fun `both off on wifi, switch wireless debugging on`() {
        assertEquals(AdbdRevival.ENABLE_WIRELESS_DEBUGGING, decide())
    }

    @Test
    fun `no wifi means wireless debugging cannot run, so nothing is written`() {
        assertEquals(AdbdRevival.NEEDS_WIFI, decide(wdOn = true, wifi = WifiState.NOT_CONNECTED))
        assertEquals(AdbdRevival.NEEDS_WIFI, decide(wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `an unknown wifi state still tries`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true, wifi = WifiState.UNKNOWN))
    }

    @Test
    fun `without the grant the switches cannot be written`() {
        assertEquals(AdbdRevival.NO_GRANT, decide(wdOn = true, hasGrant = false))
    }

    @Test
    fun `when switching it on is not allowed, both-off is left alone`() {
        // Shizuku mode, or a switch the user turned off: only a cycle that ends where it started is ok.
        assertEquals(AdbdRevival.NOTHING, decide(mayEnable = false))
    }

    @Test
    fun `a cycle is still allowed, because it ends with the switch as it was`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true, mayEnable = false))
    }

    // --- A live Shizuku server contradicts a stopped reading, and it is the reliable half (#39) ---

    @Test
    fun `leaves the switches alone while a Shizuku server still answers`() {
        // The hazard: cycling here restarts a live adbd and kills the Shizuku server it is hosting —
        // the reporter's "it disables automatically within a second".
        assertEquals(AdbdRevival.NOTHING, decide(wdOn = true, shizukuServerRunning = true))
    }

    @Test
    fun `a live Shizuku server also blocks switching Wireless debugging on from off`() {
        assertEquals(AdbdRevival.NOTHING, decide(wdOn = false, shizukuServerRunning = true))
    }

    @Test
    fun `without a Shizuku server the stopped reading is acted on as before`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true, shizukuServerRunning = false))
        assertEquals(AdbdRevival.ENABLE_WIRELESS_DEBUGGING, decide(wdOn = false, shizukuServerRunning = false))
    }

    @Test
    fun `USB debugging still wins over the Shizuku check`() {
        // Nothing to do either way; the order of the two NOTHING branches must not change the answer.
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, shizukuServerRunning = true))
    }
}
