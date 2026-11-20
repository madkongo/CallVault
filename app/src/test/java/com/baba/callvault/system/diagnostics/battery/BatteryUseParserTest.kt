/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batterystats cut read into [BatteryUse]: the model a report section (and later an in-app page) is drawn from.
 * Fixtures are built from AOSP `BatteryStats.dumpCheckinLocked` (checkin version 9) in three shapes: Android 14,
 * Android 11 with a root recorder, and a future one with extra trailing fields on every row.
 */
class BatteryUseParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun parse(name: String, recorderUid: Int = 2000, appUid: Int? = null, viaRecorder: Boolean = true): BatteryUse? {
        val raw = fixture(name)
        val text = if (viaRecorder) BatteryCheckin.cut(raw.lineSequence(), PACKAGE, recorderUid, null) else raw
        return BatteryUseParser.parse(text, PACKAGE, recorderUid, appUid)
    }

    @Test
    fun `the period since the last full charge`() {
        val use = parse("batterystats_checkin_a14.txt")!!
        assertEquals(1_795_161_600_000L, use.sinceMillis)
        assertEquals(21_600_000L, use.onBatteryMs)
        assertEquals(18_000_000L, use.screenOffMs)
        assertEquals(3_600_000L, use.screenOnMs)
        assertEquals(38, use.dropLowPercent)
        assertEquals(39, use.dropHighPercent)
        assertEquals(1_650.5, use.computedDrainMah!!, 0.01)
    }

    @Test
    fun `the app's own numbers`() {
        val app = parse("batterystats_checkin_a14.txt")!!.app!!
        assertEquals(10234, app.uid)
        assertEquals(130_000L, app.cpuMs)
        assertEquals(12, app.partialWakeLockCount)
        assertEquals(40_000L, app.partialWakeLockMs)
        assertEquals(3, app.wakeupAlarms)
        assertEquals(12, app.jobCount)
        assertEquals(40_000L, app.jobMs)
        assertEquals(21_500_000L, app.foregroundServiceMs)
        assertEquals(1_200_000L, app.wifiRxBytes)
        assertEquals(300_000L, app.wifiTxBytes)
        assertEquals(0L, app.mobileRxBytes)
        assertEquals(12.3, app.powerMah!!, 0.01)
        assertEquals(3, app.processStarts)
        assertEquals("*job*/com.baba.callvault/androidx.work.impl.background.systemjob.SystemJobService", app.topWakeLocks.first().name)
    }

    @Test
    fun `the shell uid holds the recorder and adbd`() {
        val shell = parse("batterystats_checkin_a14.txt")!!.shell!!
        assertEquals(842_000L, shell.cpuMs)
        assertEquals(3, shell.partialWakeLockCount)
        assertEquals(72_000L, shell.partialWakeLockMs)
        assertEquals(40.1, shell.powerMah!!, 0.01)
        assertEquals(BatteryCheckin.OTHER_NAME, shell.topWakeLocks.single().name)
    }

    @Test
    fun `root is only reported when the recorder runs as root`() {
        assertNull(parse("batterystats_checkin_a14.txt")!!.root)
        val use = parse("batterystats_checkin_a11_root.txt", recorderUid = 0)!!
        assertEquals(480_000L, use.root!!.cpuMs)
        assertEquals(55.0, use.root!!.powerMah!!, 0.01)
        assertEquals(10301, use.app!!.uid)
        assertEquals(10_700_000L, use.app!!.foregroundServiceMs)
        assertEquals(90_000L, use.shell!!.cpuMs)
    }

    @Test
    fun `aggregated wake lock time wins over the per-name sum, which double counts overlaps`() {
        val raw = """
            9,0,l,bt,0,3600000,1,1,1,0,1,1,0,0,0,0
            9,10234,l,wl,a,0,f,0,0,0,0,30000,p,1,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0
            9,10234,l,wl,b,0,f,0,0,0,0,30000,p,1,0,0,0,0,bp,0,0,0,0,0,w,0,0,0,0
            9,10234,l,awl,35000,0
        """.trimIndent()
        val app = BatteryUseParser.parse(raw, PACKAGE, 2000, 10234)!!.app!!
        assertEquals(35_000L, app.partialWakeLockMs)
        assertEquals(2, app.partialWakeLockCount)
    }

    @Test
    fun `extra trailing fields from a newer Android are ignored`() {
        val use = parse("batterystats_checkin_a16_extra_fields.txt")!!
        assertEquals(7_200_000L, use.onBatteryMs)
        assertEquals(40_000L, use.app!!.cpuMs)
        assertEquals(2_500L, use.app!!.mobileRadioActiveMs)
        assertEquals(75_000L, use.shell!!.cpuMs)
    }

    @Test
    fun `a raw dump from the ADB fallback is cut on the app side with the uid the app knows`() {
        val use = parse("batterystats_checkin_a14.txt", appUid = 10234, viaRecorder = false)!!
        assertEquals(130_000L, use.app!!.cpuMs)
        assertEquals(BatteryCheckin.OTHER_NAME, use.shell!!.topWakeLocks.single().name)
    }

    @Test
    fun `a uid with no rows is absent rather than zero`() {
        val raw = "9,0,l,bt,0,3600000,1,1,1,0,1,1,0,0,0,0\n9,10234,l,cpu,10,20,0"
        val use = BatteryUseParser.parse(raw, PACKAGE, 2000, 10234)!!
        assertNotNull(use.app)
        assertNull(use.shell)
    }

    @Test
    fun `no battery row means nothing could be read`() {
        assertNull(BatteryUseParser.parse("Permission Denial: can't dump BatteryStats from pid=1", PACKAGE, 2000, 10234))
        assertNull(BatteryUseParser.parse("", PACKAGE, 2000, 10234))
    }

    @Test
    fun `a partial or truncated cut carries its note`() {
        val text = BatteryCheckin.HEADER + "\n9,0,l,bt,0,3600000,1,1,1,0,1,1,0,0,0,0\n# truncated: 5 more rows left out\n# partial: batterystats timed out after 15 s\n"
        val use = BatteryUseParser.parse(text, PACKAGE, 2000, 10234)!!
        assertTrue(use.notes.toString(), use.notes.any { it.contains("timed out") })
        assertTrue(use.notes.any { it.contains("5 more rows") })
    }

    @Test
    fun `garbage numbers do not throw`() {
        val raw = "9,0,l,bt,0,abc,1,1,1,x,1,1\n9,10234,l,cpu,,,\n9,10234,l,wl,n,p\n9,10234,l,nt,1"
        val use = BatteryUseParser.parse(raw, PACKAGE, 2000, 10234)
        assertNotNull(use)
        assertEquals(0L, use!!.onBatteryMs)
    }

    private companion object {
        const val PACKAGE = "com.baba.callvault"
    }
}
