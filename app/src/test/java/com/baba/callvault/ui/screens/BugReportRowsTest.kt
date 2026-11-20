/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings ▸ Help and about ▸ Debug: Share and Save are offered with logging on and off (owner report 2026-11-20,
 * OP9: they only appeared after logging was turned off). The always-on sections make a report worth sending either way.
 */
class BugReportRowsTest {

    @Test
    fun `share and save are shown while logging is on, under the warning`() {
        val rows = BugReportRows.of(isLoggingEnabled = true)
        assertTrue(rows.showShareAndSave)
        assertTrue(rows.showLoggingOnWarning)
        // "Logs from your last debug session are ready" is not true while one is still running.
        assertFalse(rows.showLastSessionHint)
    }

    @Test
    fun `share and save are shown while logging is off, with no warning`() {
        val rows = BugReportRows.of(isLoggingEnabled = false)
        assertTrue(rows.showShareAndSave)
        assertFalse(rows.showLoggingOnWarning)
        assertTrue(rows.showLastSessionHint)
    }

    private val screensDir = listOf(
        "src/main/java/com/baba/callvault/ui/screens",
        "app/src/main/java/com/baba/callvault/ui/screens",
    ).map(::File).first { it.isDirectory }

    @Test
    fun `the debug section draws its buttons from BugReportRows, not from the logging switch`() {
        val debug = File(screensDir, "SettingsAboutDebugSection.kt").readText()
        val section = debug.substring(debug.indexOf("fun BugReportSection("), debug.indexOf("fun DebugLogViewer("))
        assertTrue(section.contains("BugReportRows.of("))
        assertTrue(section.contains("if (rows.showShareAndSave)"))
        assertFalse("Share/Save are gated on the logging switch again", section.contains("} else {"))
    }
}
