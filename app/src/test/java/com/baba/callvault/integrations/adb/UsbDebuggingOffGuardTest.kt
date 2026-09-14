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
 * What turning USB debugging off costs, decided before the switch is written.
 *
 * AOSP stops adbd when both debugging flags are off, and clears the Wireless-debugging flag itself when
 * Wi-Fi drops. So with USB debugging off, off-Wi-Fi recording cannot work at all — the loopback listener
 * lives inside adbd — and with no Wi-Fi right now, nothing can bring the recorder back. #39 walked into
 * both through this toggle with no warning.
 */
class UsbDebuggingOffGuardTest {

    @Test
    fun `turning it on never needs a warning`() {
        assertEquals(
            UsbDebuggingOff.PROCEED,
            UsbDebuggingOffGuard.decide(turnOn = true, offlineRecordingOn = true, wifi = WifiState.NOT_CONNECTED),
        )
    }

    @Test
    fun `off wifi right now there is no way back in`() {
        assertEquals(
            UsbDebuggingOff.WARN_NO_WAY_IN,
            UsbDebuggingOffGuard.decide(turnOn = false, offlineRecordingOn = false, wifi = WifiState.NOT_CONNECTED),
        )
    }

    @Test
    fun `no way in outranks losing off-wifi recording`() {
        assertEquals(
            UsbDebuggingOff.WARN_NO_WAY_IN,
            UsbDebuggingOffGuard.decide(turnOn = false, offlineRecordingOn = true, wifi = WifiState.NOT_CONNECTED),
        )
    }

    @Test
    fun `on wifi with offline recording, off-wifi recording is what is lost`() {
        assertEquals(
            UsbDebuggingOff.WARN_LOSES_OFFLINE,
            UsbDebuggingOffGuard.decide(turnOn = false, offlineRecordingOn = true, wifi = WifiState.CONNECTED),
        )
    }

    @Test
    fun `on wifi without offline recording nothing is lost`() {
        assertEquals(
            UsbDebuggingOff.PROCEED,
            UsbDebuggingOffGuard.decide(turnOn = false, offlineRecordingOn = false, wifi = WifiState.CONNECTED),
        )
    }

    @Test
    fun `an unknown wifi state still warns about offline recording, never about no way in`() {
        assertEquals(
            UsbDebuggingOff.WARN_LOSES_OFFLINE,
            UsbDebuggingOffGuard.decide(turnOn = false, offlineRecordingOn = true, wifi = WifiState.UNKNOWN),
        )
        assertEquals(
            UsbDebuggingOff.PROCEED,
            UsbDebuggingOffGuard.decide(turnOn = false, offlineRecordingOn = false, wifi = WifiState.UNKNOWN),
        )
    }
}
