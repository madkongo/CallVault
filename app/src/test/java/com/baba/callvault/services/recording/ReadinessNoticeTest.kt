/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.integrations.adb.WifiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which readiness notice the permanent notification shows.
 *
 * #23, #24 and #39 all sat on "Call recorder starting up…" while recovery could not succeed. "Starting"
 * is a claim that it will finish; it may only be shown while a restart is still possible.
 * See docs/dev-notes/2026-09-14-debugging-switches-model.md for the measured rules.
 */
class ReadinessNoticeTest {

    private fun of(
        ready: Boolean = false,
        stuck: Boolean = false,
        usbOn: Boolean = true,
        wdOn: Boolean = false,
        wifi: WifiState = WifiState.CONNECTED,
        loopbackArmed: Boolean = false,
        hasGrant: Boolean = true,
        wdOffByUser: Boolean = false,
        enforced: Boolean = false,
        offlineOn: Boolean = false,
    ) = ReadinessNotice.of(ready, stuck, usbOn, wdOn, wifi, loopbackArmed, hasGrant, wdOffByUser, enforced, offlineOn)

    @Test
    fun `ready wins over everything`() {
        assertEquals(ReadinessNotice.READY, of(ready = true, stuck = true, usbOn = false, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `an ordinary relaunch reads as starting`() {
        assertEquals(ReadinessNotice.STARTING, of())
    }

    @Test
    fun `usb off and no wifi cannot recover, whatever the wireless switch reads`() {
        // Android clears Wireless debugging off Wi-Fi, so "turn one back on" would be wrong advice here.
        assertEquals(ReadinessNotice.NEEDS_WIFI, of(usbOn = false, wdOn = true, wifi = WifiState.NOT_CONNECTED))
        assertEquals(ReadinessNotice.NEEDS_WIFI, of(usbOn = false, wdOn = false, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `usb on without an armed loopback still needs wifi to restart`() {
        // Measured as T3: the recorder stayed down off Wi-Fi and the old notice said "starting up".
        assertEquals(ReadinessNotice.NEEDS_WIFI_TO_RESTART, of(usbOn = true, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `usb on with an armed loopback restarts off wifi`() {
        // Measured as T5b: relaunched over the loopback in under a second.
        assertEquals(ReadinessNotice.STARTING, of(usbOn = true, wifi = WifiState.NOT_CONNECTED, loopbackArmed = true))
    }

    @Test
    fun `both off on wifi is recoverable by the app itself`() {
        assertEquals(ReadinessNotice.STARTING, of(usbOn = false, wdOn = false))
    }

    @Test
    fun `both off without the grant needs the user`() {
        assertEquals(ReadinessNotice.NO_DEBUGGING, of(usbOn = false, wdOn = false, hasGrant = false))
    }

    @Test
    fun `an unknown wifi state is not evidence of no wifi`() {
        assertEquals(ReadinessNotice.STARTING, of(usbOn = false, wdOn = true, wifi = WifiState.UNKNOWN))
    }

    @Test
    fun `a tripped recovery streak stops claiming it is starting`() {
        assertEquals(ReadinessNotice.STUCK, of(stuck = true))
    }

    @Test
    fun `a named cause outranks the generic stuck notice`() {
        assertEquals(ReadinessNotice.NEEDS_WIFI, of(stuck = true, usbOn = false, wdOn = true, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `a wireless switch the user turned off is named, not retried`() {
        assertEquals(ReadinessNotice.WD_OFF_BY_USER, of(usbOn = false, wdOn = false, wdOffByUser = true))
    }

    @Test
    fun `with the opt-in setting on, it is simply starting again`() {
        assertEquals(ReadinessNotice.STARTING, of(usbOn = false, wdOn = false, wdOffByUser = true, enforced = true))
    }

    @Test
    fun `after a reboot the switch is borrowable, so recovery is not declared impossible`() {
        // The 2026-09-19 deadlock. A reboot clears the off-Wi-Fi listener, the "user turned it off" flag
        // survives it, and this notice is what told the keep-alive to stop trying. With off-Wi-Fi
        // recording on and USB debugging on, one transient write gets the listener back -- so the honest
        // notice is "starting", not "you turned it off and nothing else can help".
        assertEquals(
            ReadinessNotice.STARTING,
            of(usbOn = true, wdOn = false, loopbackArmed = false, wdOffByUser = true, offlineOn = true),
        )
    }

    @Test
    fun `a user who has not enabled off-Wi-Fi recording is still simply obeyed`() {
        // Nothing to re-arm, so there is nothing to borrow the switch for and the notice stands.
        assertEquals(
            ReadinessNotice.WD_OFF_BY_USER,
            of(usbOn = true, wdOn = false, loopbackArmed = false, wdOffByUser = true, offlineOn = false),
        )
    }

    @Test
    fun `usb on with an armed loopback does not need the wireless switch at all`() {
        assertEquals(ReadinessNotice.STARTING, of(usbOn = true, wdOn = false, wdOffByUser = true, loopbackArmed = true))
    }

    @Test
    fun `off-wifi recording without usb debugging is ready, but paused`() {
        assertEquals(ReadinessNotice.READY_OFFLINE_PAUSED, of(ready = true, usbOn = false, offlineOn = true))
    }

    @Test
    fun `off-wifi recording with usb debugging is plainly ready`() {
        assertEquals(ReadinessNotice.READY, of(ready = true, usbOn = true, offlineOn = true))
    }
}
