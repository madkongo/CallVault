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
 * is a claim that it will finish; it may only be shown while that is still possible.
 */
class ReadinessNoticeTest {

    private fun of(
        ready: Boolean = false,
        stuck: Boolean = false,
        usbOn: Boolean = true,
        wdOn: Boolean = false,
        wifi: WifiState = WifiState.CONNECTED,
    ) = ReadinessNotice.of(ready, stuck, usbOn, wdOn, wifi)

    @Test
    fun `ready wins over everything`() {
        assertEquals(ReadinessNotice.READY, of(ready = true, stuck = true, usbOn = false, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `an ordinary relaunch reads as starting`() {
        assertEquals(ReadinessNotice.STARTING, of())
    }

    @Test
    fun `both switches off is known at once, without waiting for the streak`() {
        assertEquals(ReadinessNotice.NO_DEBUGGING, of(usbOn = false, wdOn = false))
    }

    @Test
    fun `usb off and no wifi cannot recover, so it is said at once`() {
        // AOSP clears Wireless debugging off Wi-Fi, so it is irrelevant whether the flag reads on.
        assertEquals(ReadinessNotice.NEEDS_WIFI, of(usbOn = false, wdOn = true, wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `an unknown wifi state is not evidence of no wifi`() {
        assertEquals(ReadinessNotice.STARTING, of(usbOn = false, wdOn = true, wifi = WifiState.UNKNOWN))
    }

    @Test
    fun `a tripped recovery streak stops claiming it is starting`() {
        assertEquals(ReadinessNotice.STUCK, of(stuck = true))
        assertEquals(ReadinessNotice.STUCK, of(stuck = true, usbOn = false, wdOn = true, wifi = WifiState.UNKNOWN))
    }

    @Test
    fun `a named cause outranks the generic stuck notice`() {
        assertEquals(ReadinessNotice.NEEDS_WIFI, of(stuck = true, usbOn = false, wdOn = true, wifi = WifiState.NOT_CONNECTED))
    }
}
