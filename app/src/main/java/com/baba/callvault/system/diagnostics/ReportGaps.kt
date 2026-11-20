/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * The "what's missing" line at the top of a report.
 *
 * A section that could not be collected used to just be absent, and a reader cannot tell "nothing happened" from
 * "we could not look" — so every report names, in one line near its top, each part it lacks and why.
 */
object ReportGaps {

    private const val PREFIX = "Missing from this report: "
    private const val NOTHING = "nothing"

    /** The line for [gaps]; "nothing" when the report is complete. */
    fun line(gaps: List<String>): String = PREFIX + (gaps.ifEmpty { listOf(NOTHING) }).joinToString("; ") + "."

    /**
     * [report] with [gap] added to its missing line — for a part found missing after the header was written,
     * such as the system report timing out. A report without the line gets one at the top.
     */
    fun withGap(report: String, gap: String): String {
        val lines = report.split('\n')
        val at = lines.indexOfFirst { it.startsWith(PREFIX) }
        if (at < 0) return line(listOf(gap)) + "\n" + report
        val existing = lines[at].removePrefix(PREFIX).removeSuffix(".").split("; ").filter { it != NOTHING }
        return lines.toMutableList().apply { this[at] = line(existing + gap) }.joinToString("\n")
    }
}
