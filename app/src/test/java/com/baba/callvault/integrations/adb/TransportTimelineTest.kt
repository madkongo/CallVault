/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the always-on timeline writes, and what counts as a change worth a line (R51, R52, R5). */
class TransportTimelineTest {

    private val base = TransportTimeline.Facts(
        recorderAlive = true, adbd = "up", usb = "ON", usbShell = "on", wirelessDebugging = true, wifi = "CONNECTED",
        offlinePortArmed = false, screen = "on", locked = false, chargeOnlyAdb = "absent",
        powerSave = false, batteryExempt = true, backgroundRestricted = false, bucketRestricted = false,
    )

    @Test
    fun `a screen change alone is not a new state to write`() {
        val off = base.copy(screen = "off", locked = true)
        assertEquals(TransportTimeline.changeKey(base), TransportTimeline.changeKey(off))
    }

    @Test
    fun `the line that is written still says what the screen was`() {
        assertTrue(TransportTimeline.render(base.copy(screen = "off", locked = true)).contains("screen=off+locked"))
    }

    @Test
    fun `battery saver turning on is a new state and is written`() {
        val saver = base.copy(powerSave = true)
        assertNotEquals(TransportTimeline.changeKey(base), TransportTimeline.changeKey(saver))
        assertTrue(TransportTimeline.render(saver).contains("batterySaver=ON"))
    }

    @Test
    fun `losing the battery exemption, background restriction and the restricted bucket are each written`() {
        for (changed in listOf(
            base.copy(batteryExempt = false),
            base.copy(backgroundRestricted = true),
            base.copy(bucketRestricted = true),
        )) {
            assertNotEquals(TransportTimeline.changeKey(base), TransportTimeline.changeKey(changed))
        }
        val line = TransportTimeline.render(base.copy(batteryExempt = false, backgroundRestricted = true, bucketRestricted = true))
        assertTrue(line, line.contains("batteryOpt=ON") && line.contains("bgRestricted=YES") && line.contains("bucket=RESTRICTED"))
    }

    @Test
    fun `a recorder going down is still a new state`() {
        assertNotEquals(TransportTimeline.changeKey(base), TransportTimeline.changeKey(base.copy(recorderAlive = false)))
    }
}
