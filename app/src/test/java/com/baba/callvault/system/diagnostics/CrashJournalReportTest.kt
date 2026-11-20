/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.system.diagnostics.battery.BatteryUseCollector
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The crash section is in every debug report: the crashes, or a line saying there were none. Never just absent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashJournalReportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun report(): String = runBlocking {
        AppLogger.buildShareableReport(app) {
            BatteryUseCollector.section(BatteryUseCollector.Fetched(null, "not read in this test"), "com.baba.callvault", 10234, null)
        }
    }!!.readText()

    @Test
    fun `with no crashes the report says none were recorded`() {
        CrashJournal.clear(app)
        val text = report()
        assertTrue(text, text.contains("Crashes (always on): none recorded"))
        assertFalse(text.contains("--- Crashes (always on; newest last) ---"))
    }

    @Test
    fun `with a crash the report carries it under its heading`() {
        CrashJournal.clear(app)
        CrashJournal.record(app, Thread.currentThread(), IllegalStateException("boom for the report"))
        val text = report()
        assertTrue(text, text.contains("--- Crashes (always on; newest last) ---"))
        assertTrue(text, text.contains("boom for the report"))
        assertFalse(text.contains("none recorded"))
        CrashJournal.clear(app)
    }
}
