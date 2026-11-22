/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.io.File
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

    // --- GH89-5: a death line carries what the phone says about adbd and the USB gadget ---

    @Test
    fun `the death line names adbd, sys usb config and sys usb state, and says so when one is unreadable`() {
        val line = TransportTimeline.deathLine(adbd = "STOPPED", usbConfig = "mtp,adb", usbState = "")
        assertTrue(line, line.contains("init.svc.adbd=STOPPED"))
        assertTrue(line, line.contains("sys.usb.config=mtp,adb"))
        assertTrue(line, line.contains("sys.usb.state=(unreadable)"))
    }

    @Test
    fun `the state line carries the usb gadget properties too, and a change in them is a new state`() {
        val moved = base.copy(usbConfig = "none")
        assertTrue(TransportTimeline.render(moved).contains("sys.usb.config=none"))
        assertNotEquals(TransportTimeline.changeKey(base), TransportTimeline.changeKey(moved))
    }

    @Test
    fun `the keep-alive writes the death line in every mode, before the adb-only branch`() {
        val src = listOf(
            "src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt",
            "app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt",
        ).map(::File).first { it.exists() }.readText()
        val a = src.indexOf("private fun onDaemonDiedImmediate()")
        val died = src.indexOf("TransportTimeline.recorderDied(", a)
        val adbOnly = src.indexOf("if (usesAdb())", a)
        assertTrue(a > 0 && died > a && adbOnly > died)
    }
}
