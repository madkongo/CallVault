/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A report must never lack a part silently: a reader cannot tell "nothing happened" from "we could not look". */
class ReportGapsTest {

    @Test
    fun `a complete report says nothing is missing`() {
        assertEquals("Missing from this report: nothing.", ReportGaps.line(emptyList()))
    }

    @Test
    fun `each missing part is named`() {
        assertEquals(
            "Missing from this report: system log (logcat unreadable); recorder host lines (recorder not connected).",
            ReportGaps.line(listOf("system log (logcat unreadable)", "recorder host lines (recorder not connected)")),
        )
    }

    @Test
    fun `a gap found after the header was written is added to its line`() {
        val report = "=== header ===\n${ReportGaps.line(emptyList())}\nbody"
        val updated = ReportGaps.withGap(report, "the system report (not collected within 45 s)")
        assertEquals(
            "=== header ===\nMissing from this report: the system report (not collected within 45 s).\nbody",
            updated,
        )
        val twice = ReportGaps.withGap(updated, "x")
        assertTrue(twice, twice.contains("Missing from this report: the system report (not collected within 45 s); x.\n"))
    }

    @Test
    fun `a report without the line gets one at the top`() {
        assertEquals("Missing from this report: x.\nbody", ReportGaps.withGap("body", "x"))
    }
}
