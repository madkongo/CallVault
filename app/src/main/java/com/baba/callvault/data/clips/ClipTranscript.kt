/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.clips

/** One transcript line as the clip sees it: when, optionally who, and what. */
data class ClipLine(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null)

/**
 * The transcript text that goes with a shared clip — only what was said inside it (maintainer, 2026-11-04: "sharing a
 * clip will share only the clipped transcript").
 *
 * Pure, so the rules are tested.
 */
object ClipTranscript {

    /**
     * The lines that belong to [range]: any line whose middle falls inside it. By the middle so a line that starts
     * just before the clip and is mostly heard in it is kept, and one that only grazes an edge is not.
     */
    fun linesIn(lines: List<ClipLine>, range: ClipRange): List<ClipLine> =
        lines.filter { (it.startMs + it.endMs) / 2 in range.startMs..range.endMs && it.text.isNotBlank() }

    /**
     * Lines transcribed from the clip file itself, whose times start at the clip's zero, moved back onto the call's
     * clock so they read the same as the call's own transcript would.
     */
    fun fromClipFile(lines: List<ClipLine>, range: ClipRange, leadInMs: Long): List<ClipLine> =
        lines.map {
            val shift = range.startMs - leadInMs
            it.copy(startMs = (it.startMs + shift).coerceAtLeast(0L), endMs = (it.endMs + shift).coerceAtLeast(0L))
        }.let { linesIn(it, range) }

    /** `[1:12] Dana: text`, one per line, times on the call's clock. */
    fun render(lines: List<ClipLine>): String = lines.joinToString("\n") { line ->
        val who = line.speaker?.takeIf { it.isNotBlank() }?.let { "$it: " }.orEmpty()
        "[${ClipRange.clock(line.startMs)}] $who${line.text.trim()}"
    }
}
