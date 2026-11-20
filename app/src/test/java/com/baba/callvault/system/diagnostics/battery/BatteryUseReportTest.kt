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
import java.util.TimeZone

/** The "Battery use" section as a human reads it, and its one-line verdict. */
class BatteryUseReportTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun a14() = BatteryUseParser.parse(fixture("batterystats_checkin_a14.txt"), "com.baba.callvault", 2000, null)!!

    private fun quiet(onBatteryMs: Long = 10 * hour) = BatteryUse(
        sinceMillis = 1_795_161_600_000L, onBatteryMs = onBatteryMs, screenOffMs = onBatteryMs / 2, screenOnMs = onBatteryMs / 2,
        dropLowPercent = 20, dropHighPercent = 21, computedDrainMah = 1_000.0, recorderUid = 2000,
        app = UidUse(10234, cpuUserMs = 10_000, cpuSystemMs = 5_000, powerMah = 5.0),
        shell = UidUse(2000, cpuUserMs = 20_000, cpuSystemMs = 10_000, powerMah = 8.0),
        root = null, notes = emptyList(),
    )

    @Test
    fun `the section reads as plain lines`() {
        val lines = BatteryUseReport.lines(a14(), RecorderActivityCounts.LogCounts(3, 2, 0, 1_795_161_000_000L), utc)
        val text = lines.joinToString("\n")
        assertTrue(text, lines.first().startsWith("Period: 6h00m on battery (screen off 5h00m, screen on 1h00m), battery dropped 38-39%, since 2026-11-20 08:00"))
        assertTrue(text, text.contains("CallVault app (uid 10234): CPU 2m10s, wake locks 12 (40s), alarms 3, jobs 12 (40s), foreground service 5h58m"))
        assertTrue(text, text.contains("Android System / shell uid 2000 (the recorder, adbd, and any other adb or Shizuku tool): CPU 14m02s, wake locks 3 (1m12s)"))
        assertTrue(text, text.contains("est. 40.1 mAh"))
        // The shell's process rows are com.android.shell, not the recorder: not shown.
        assertFalse(text, lines.first { it.startsWith("Android System") }.contains("process starts"))
        assertTrue(text, text.contains("From CallVault's own log since then: 3 recorder launch attempts, 2 keep-alive relaunches, 0 ADB connection rebuilds."))
        assertTrue(text, lines.last().startsWith("Verdict: "))
    }

    @Test
    fun `logging off is said, not shown as zero`() {
        val text = BatteryUseReport.lines(a14(), null, utc).joinToString("\n")
        assertTrue(text, text.contains("From CallVault's own log: not counted (debug logging is off)."))
    }

    @Test
    fun `a log that starts after the period began says so`() {
        val text = BatteryUseReport.lines(quiet(), RecorderActivityCounts.LogCounts(0, 0, 0, 1_795_200_000_000L), utc).joinToString("\n")
        assertTrue(text, text.contains("(the log only goes back to 2026-11-20 18:40)"))
    }

    @Test
    fun `root is shown only for a root recorder, and labelled as shared`() {
        assertFalse(BatteryUseReport.lines(a14(), null, utc).any { it.startsWith("Root uid 0") })
        val root = BatteryUseParser.parse(fixture("batterystats_checkin_a11_root.txt"), "com.baba.callvault", 0, null)!!
        assertTrue(BatteryUseReport.lines(root, null, utc).any { it.startsWith("Root uid 0 (the recorder runs as root; also every other root daemon): CPU 8m00s") })
    }

    @Test
    fun `a missing uid is said plainly`() {
        val text = BatteryUseReport.lines(quiet().copy(app = null), null, utc).joinToString("\n")
        assertTrue(text, text.contains("CallVault app: no row in batterystats"))
    }

    @Test
    fun `quiet numbers look normal`() {
        assertEquals("looks normal by these numbers", BatteryUseReport.verdict(quiet(), null))
    }

    @Test
    fun `too short a period is not judged`() {
        assertTrue(BatteryUseReport.verdict(quiet(onBatteryMs = hour - 1), null).startsWith("too little time on battery"))
    }

    @Test
    fun `the fixture's shell CPU is high, and the verdict says the shell is shared`() {
        val v = BatteryUseReport.verdict(a14(), null)
        assertTrue(v, v.startsWith("high: CPU 16m12s in 6h00m on battery"))
        assertTrue(v, v.contains("shell also counts adbd"))
    }

    @Test
    fun `CPU threshold`() {
        val under = quiet().copy(shell = UidUse(2000, cpuUserMs = 10 * BatteryUseReport.CPU_HIGH_MS_PER_HOUR - 15_000 - 1))
        val over = quiet().copy(shell = UidUse(2000, cpuUserMs = 10 * BatteryUseReport.CPU_HIGH_MS_PER_HOUR))
        assertEquals("looks normal by these numbers", BatteryUseReport.verdict(under, null))
        assertTrue(BatteryUseReport.verdict(over, null).startsWith("high: CPU"))
    }

    @Test
    fun `wake lock threshold`() {
        val held = (10 * hour * BatteryUseReport.WAKE_HIGH_FRACTION).toLong()
        val over = quiet().copy(app = UidUse(10234, wakeLocks = listOf(NamedUse("w", 4, held + 1))))
        val under = quiet().copy(app = UidUse(10234, wakeLocks = listOf(NamedUse("w", 4, held - 1))))
        assertTrue(BatteryUseReport.verdict(over, null).contains("wake locks held"))
        assertEquals("looks normal by these numbers", BatteryUseReport.verdict(under, null))
    }

    @Test
    fun `alarm threshold`() {
        val over = quiet().copy(app = UidUse(10234, wakeupAlarms = 10 * BatteryUseReport.ALARMS_HIGH_PER_HOUR + 1))
        val under = quiet().copy(app = UidUse(10234, wakeupAlarms = 10 * BatteryUseReport.ALARMS_HIGH_PER_HOUR))
        assertTrue(BatteryUseReport.verdict(over, null).contains("wake-up alarms"))
        assertEquals("looks normal by these numbers", BatteryUseReport.verdict(under, null))
    }

    @Test
    fun `power share threshold`() {
        val over = quiet().copy(app = UidUse(10234, powerMah = 1_000 * BatteryUseReport.POWER_SHARE_HIGH))
        assertTrue(BatteryUseReport.verdict(over, null), BatteryUseReport.verdict(over, null).contains("of Android's 1000 mAh estimate"))
    }

    @Test
    fun `launch threshold`() {
        val over = RecorderActivityCounts.LogCounts(launchAttempts = 800, relaunches = 40, adbRebuilds = 0, logStartMillis = 0)
        val v = BatteryUseReport.verdict(quiet(), over)
        // A keep-alive relaunch is followed by a launch attempt, so the two are not added: the larger one is said.
        assertTrue(v, v.startsWith("high: 800 recorder start attempts in the log since the charge"))
        val under = RecorderActivityCounts.LogCounts(launchAttempts = 5, relaunches = 0, adbRebuilds = 0, logStartMillis = 0)
        assertEquals("looks normal by these numbers", BatteryUseReport.verdict(quiet(), under))
    }

    @Test
    fun `durations and sizes`() {
        assertEquals("0s", BatteryUseReport.duration(0))
        assertEquals("59s", BatteryUseReport.duration(59_999))
        assertEquals("2m10s", BatteryUseReport.duration(130_000))
        assertEquals("6h00m", BatteryUseReport.duration(21_600_000))
        assertEquals("0 B", BatteryUseReport.bytes(0))
        assertEquals("1.5 MB", BatteryUseReport.bytes(1_500_000))
        assertEquals("900 KB", BatteryUseReport.bytes(900_000))
    }
}
