/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real `dumpsys batterystats -c com.android.shell` answers from Google emulator images, API 30 to 37 (Android 11-17),
 * read 2026-11-20 — kept to the version row, the device-wide rows and the shell uid's rows. Every API wrote row
 * format 9; the report version was 35 on API 30-33 and 36 on API 34-37 (Android 17 included). The emulators run on
 * "charging", so the time on battery is zero, and they report no per-uid CPU or wake-lock rows for the shell; those
 * layouts rest on the real OP9 sample (Android 14).
 */
class BatteryUseEmulatorLayoutsTest {

    private val reportVersionByApi = mapOf(30 to 35, 31 to 35, 33 to 35, 34 to 36, 35 to 36, 36 to 36, 37 to 36)

    private fun fixture(api: Int): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("batterystats_emulator_api$api.txt")) { "missing fixture for API $api" }
            .bufferedReader().readText()

    @Test
    fun `every real layout from Android 11 to 17 is recognised`() {
        reportVersionByApi.forEach { (api, version) ->
            val text = fixture(api)
            assertTrue("API $api", text.lines().first().startsWith("9,0,i,vers,$version,"))
            val use = BatteryUseParser.parse(text, "com.baba.callvault", 2000, null)
            assertNull("API $api: ${use?.formatProblem}", use!!.formatProblem)
        }
    }

    @Test
    fun `the device rows read on every API`() {
        reportVersionByApi.keys.forEach { api ->
            val use = BatteryUseParser.parse(fixture(api), "com.baba.callvault", 2000, null)!!
            assertEquals("API $api", 0L, use.onBatteryMs)
            assertEquals("API $api", 0, use.dropLowPercent)
        }
        // From API 33 on, the drain summary and the shell's power row are there.
        assertEquals(0.0, BatteryUseParser.parse(fixture(37), "com.baba.callvault", 2000, null)!!.computedDrainMah!!, 0.001)
        assertTrue(BatteryUseParser.parse(fixture(37), "com.baba.callvault", 2000, null)!!.shell != null)
        assertNull(BatteryUseParser.parse(fixture(30), "com.baba.callvault", 2000, null)!!.computedDrainMah)
    }

    @Test
    fun `an emulator on the charger gets no false all-clear`() {
        val v = BatteryUseReport.verdict(BatteryUseParser.parse(fixture(37), "com.baba.callvault", 2000, null)!!, null)
        assertTrue(v, v.startsWith("cannot judge") || v.startsWith("too little time"))
    }

    @Test
    fun `only the report versions actually seen are accepted`() {
        listOf(34, 37).forEach { version ->
            val text = fixture(37).replaceFirst("9,0,i,vers,36,", "9,0,i,vers,$version,")
            assertEquals("report version $version", BatteryUseParser.parse(text, "com.baba.callvault", 2000, null)!!.formatProblem)
        }
    }
}
