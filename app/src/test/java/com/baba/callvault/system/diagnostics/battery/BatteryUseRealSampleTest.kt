/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real `dumpsys batterystats --checkin` from the OP9 (OxygenOS, Android 14, 2026-11-20, two idle hours with the
 * battery reported as unplugged), already passed through [BatteryCheckin.cut] — so the fixture holds only CallVault's
 * uid, the shell uid and the device-wide rows. The phone's battery reporting was faked for the run, so its level drop
 * and the platform's drain estimate are zero.
 */
class BatteryUseRealSampleTest {

    private val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("batterystats_checkin_op9_a14_real.txt"))
        .bufferedReader().readText()

    private val use = BatteryUseParser.parse(text, "com.baba.callvault", 2000, null)!!

    @Test
    fun `the real sample parses`() {
        assertEquals(10311, use.app!!.uid)
        assertEquals(7_213_367L, use.onBatteryMs)
        assertEquals(8_399L, use.app!!.cpuMs)
        assertEquals(5_602L, use.app!!.partialWakeLockMs)
        assertEquals(6, use.app!!.jobCount)
        assertEquals(615L, use.shell!!.cpuMs)
    }

    @Test
    fun `sub-second CPU is not shown as zero`() {
        val shellLine = BatteryUseReport.lines(use, null).first { it.startsWith("Android System") }
        assertTrue(shellLine, shellLine.contains("CPU <1s"))
    }

    @Test
    fun `a zero drain estimate is left out rather than shown as measured`() {
        assertFalse(BatteryUseReport.lines(use, null).first().contains("estimate of all drain"))
    }

    @Test
    fun `two idle hours look normal`() {
        assertEquals(BatteryUseReport.NORMAL, BatteryUseReport.verdict(use, null))
    }
}
