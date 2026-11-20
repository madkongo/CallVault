/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Battery use" section in the debug report, which is read when the report is built: it must be there with
 * debug logging off, and when it cannot be read the report's "Missing from this report" line must say why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatteryUseDebugReportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun missingLine(text: String) = text.lines().first { it.startsWith("Missing from this report: ") }

    @Test
    fun `with debug logging off the report carries the battery section`() {
        AppLogger.setRingEnabled(false)
        val dump = fixture("batterystats_checkin_a14.txt")
        val report = runBlocking {
            AppLogger.buildShareableReport(app) { entries ->
                BatteryUseCollector.section(
                    BatteryUseCollector.Fetched(dump, null), "com.baba.callvault", appUid = 10234, logEntries = entries,
                )
            }
        }!!.readText()

        assertTrue(report, report.contains(BatteryUseReport.HEADING))
        assertTrue(report, report.contains("CallVault app (uid 10234): CPU 2m10s"))
        assertTrue(report, report.contains("From CallVault's own log: not counted (debug logging is off)."))
        assertTrue(report, report.lines().any { it.startsWith("Verdict: ") })
        assertFalse(missingLine(report), missingLine(report).contains("battery"))
        // Nothing about another app reaches the report.
        assertFalse(report.contains("whatsapp", ignoreCase = true))
    }

    @Test
    fun `a dump that could not be read is named on the missing line`() {
        val report = runBlocking {
            AppLogger.buildShareableReport(app) {
                BatteryUseCollector.section(
                    BatteryUseCollector.Fetched(null, "recorder not connected, and Shizuku mode has no ADB fallback"),
                    "com.baba.callvault", appUid = 10234, logEntries = null,
                )
            }
        }!!.readText()

        assertTrue(missingLine(report), missingLine(report).contains("battery use (recorder not connected, and Shizuku mode has no ADB fallback)"))
        assertTrue(report, report.contains(BatteryUseReport.HEADING))
    }

    @Test
    fun `an answer without since-charge numbers is a gap, not an empty section`() {
        val section = BatteryUseCollector.section(
            BatteryUseCollector.Fetched("Permission Denial: can't dump BatteryStats", null), "com.baba.callvault", 10234, null,
        )
        assertTrue(section.gap!!, section.gap!!.startsWith("battery use (batterystats answered without"))
    }

    @Test
    fun `the recorder's uid is taken from its cut, a raw ADB answer is the shell's`() {
        val raw = fixture("batterystats_checkin_a11_root.txt")
        val rootCut = BatteryCheckin.cut(raw.lineSequence(), "com.baba.callvault", 0, null)
        val fromRoot = BatteryUseCollector.section(BatteryUseCollector.Fetched(rootCut, null), "com.baba.callvault", 10301, null)
        assertTrue(fromRoot.lines.toString(), fromRoot.lines.any { it.startsWith("Root uid 0") })
        val fromAdb = BatteryUseCollector.section(BatteryUseCollector.Fetched(raw, null), "com.baba.callvault", 10301, null)
        assertFalse(fromAdb.lines.any { it.startsWith("Root uid 0") })
    }

    @Test
    fun `a slow fetch is cut off by the budget and named as missing`() {
        val section = runBlocking {
            BatteryUseCollector.collect(app, logEntries = null, budgetMs = 50) {
                delay(5_000)
                BatteryUseCollector.Fetched("unused", null)
            }
        }
        assertTrue(section.gap!!, section.gap!!.contains("not collected within"))
    }
}
