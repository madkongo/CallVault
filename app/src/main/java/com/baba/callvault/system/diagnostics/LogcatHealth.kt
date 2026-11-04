/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Says whether the phone's log is current, and pulls the debugging/USB lines out of all of it.
 *
 * Pure, so it is tested.
 */
object LogcatHealth {

    const val MAX_DEBUG_LINES = 150

    private val STAMP = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s")

    // Same rule as UsbOffLogWindow: capitalised forms inside identifiers, lower-case ones only at a word start.
    private val DEBUGGING = Regex("Adb|ADB|Usb|USB|(?<![A-Za-z])(adb|usb)|[Dd]ebugg|[Dd]evelop")

    fun render(raw: String, ringSizes: String?): String = buildString {
        val stamps = raw.lineSequence().mapNotNull { STAMP.find(it)?.groupValues?.get(1) }.toList()
        appendLine("--- Is the phone's log current? ---")
        appendLine("Lines read: ${stamps.size}. Oldest: ${stamps.firstOrNull() ?: "?"}. Newest: ${stamps.lastOrNull() ?: "?"}.")
        val sizes = ringSizes?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
        if (sizes.isNotEmpty()) {
            appendLine("Log buffers:")
            sizes.take(MAX_SIZE_LINES).forEach { appendLine("    $it") }
        }
    }

    fun debuggingLines(raw: String): List<String> =
        raw.lineSequence().filter { STAMP.containsMatchIn(it) && DEBUGGING.containsMatchIn(it) }.toList().takeLast(MAX_DEBUG_LINES)

    private const val MAX_SIZE_LINES = 8
}
