/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import com.baba.callvault.utils.DeviceInfo.VivoInAppRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoTest {

    @Test
    fun `vivo is detected regardless of case`() {
        assertTrue(DeviceInfo.isVivo("vivo"))
        assertTrue(DeviceInfo.isVivo("VIVO"))
        assertTrue(DeviceInfo.isVivo("Vivo"))
    }

    @Test
    fun `iQOO reports vivo as its manufacturer and is detected`() {
        // iQOO is a vivo sub-brand; its Build.MANUFACTURER is "vivo".
        assertTrue(DeviceInfo.isVivo("vivo"))
    }

    @Test
    fun `other manufacturers are not vivo`() {
        assertFalse(DeviceInfo.isVivo("samsung"))
        assertFalse(DeviceInfo.isVivo("OnePlus"))
        assertFalse(DeviceInfo.isVivo("Xiaomi"))
        assertFalse(DeviceInfo.isVivo("Google"))
        assertFalse(DeviceInfo.isVivo(""))
    }

    @Test
    fun `China firmware with the switch on has the in-app recording option`() {
        assertEquals(VivoInAppRecording.AVAILABLE, DeviceInfo.vivoInAppRecording("true", "no"))
        assertEquals(VivoInAppRecording.AVAILABLE, DeviceInfo.vivoInAppRecording("true", ""))
        assertEquals(VivoInAppRecording.AVAILABLE, DeviceInfo.vivoInAppRecording(" TRUE ", "yes"))
    }

    @Test
    fun `overseas firmware with the switch off or missing lacks the option`() {
        assertEquals(VivoInAppRecording.ABSENT, DeviceInfo.vivoInAppRecording("false", "yes"))
        assertEquals(VivoInAppRecording.ABSENT, DeviceInfo.vivoInAppRecording("", "yes"))
        assertEquals(VivoInAppRecording.ABSENT, DeviceInfo.vivoInAppRecording("", " YES "))
    }

    @Test
    fun `anything less than both signs is unknown and keeps the existing message`() {
        // Unreadable props must never tell a phone that has the option that it does not.
        assertEquals(VivoInAppRecording.UNKNOWN, DeviceInfo.vivoInAppRecording("", ""))
        assertEquals(VivoInAppRecording.UNKNOWN, DeviceInfo.vivoInAppRecording("false", ""))
        assertEquals(VivoInAppRecording.UNKNOWN, DeviceInfo.vivoInAppRecording("false", "no"))
        assertEquals(VivoInAppRecording.UNKNOWN, DeviceInfo.vivoInAppRecording("", "no"))
        assertEquals(VivoInAppRecording.UNKNOWN, DeviceInfo.vivoInAppRecording("1", "yes"))
    }
}
