/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.share

/** What a call can be shared as. The Share sheet offers one tick for each. */
enum class SharePart { RECORDING, TRANSCRIPT, SUMMARY }

/**
 * One call as the Share sheet sees it: what it is called and which of its parts exist.
 *
 * @param audioUri the copy to send (the device copy first, as the Recordings share always did), or null when the
 *   audio is gone — a "transcribe only" import, or a transcript whose recording was deleted.
 */
data class ShareableCall(
    val displayName: String,
    val title: String,
    val audioUri: String?,
    val hasTranscript: Boolean,
    val hasSummary: Boolean,
) {
    fun has(part: SharePart): Boolean = when (part) {
        SharePart.RECORDING -> audioUri != null
        SharePart.TRANSCRIPT -> hasTranscript
        SharePart.SUMMARY -> hasSummary
    }
}

/**
 * The rules of the one Share sheet every page opens, kept apart from Android so they can be tested.
 *
 * - The page's own part starts ticked; nothing is remembered between shares, so a share never quietly carries text
 *   the user did not mean to send.
 * - A part none of the calls has is not offered.
 * - With the recording, everything travels as files named after it: chat apps that take several files drop the
 *   message text, so a transcript sent as text beside audio would often vanish. Without it, text goes as a message,
 *   as the Transcripts and Summaries pages always sent it.
 */
object CallSharePlan {

    /** The parts at least one of [calls] has. */
    fun available(calls: List<ShareableCall>): Set<SharePart> =
        SharePart.entries.filter { part -> calls.any { it.has(part) } }.toSet()

    /** What starts ticked: the page's own part, or the first one there is when the page's own is missing. */
    fun initialTicks(start: SharePart, available: Set<SharePart>): Set<SharePart> = when {
        start in available -> setOf(start)
        else -> available.firstOrNull()?.let { setOf(it) } ?: emptySet()
    }

    /** Whether the share goes as files (the recording is in it) rather than as a message. */
    fun asFiles(ticks: Set<SharePart>): Boolean = SharePart.RECORDING in ticks

    /**
     * A text file's name, from the recording's own so the files stay together wherever they land:
     * "Mom_20261106_150812 — transcript.txt" beside "Mom_20261106_150812.ogg".
     */
    fun textFileName(displayName: String, word: String): String =
        "${displayName.substringBeforeLast('.', displayName)} — $word.txt"

    /**
     * One call's text when both are ticked: the summary first (the short version), then the transcript, each
     * under its heading. One part alone goes without a heading, exactly as the pages sent it before.
     */
    fun combinedText(summary: String?, transcript: String?, summaryHeading: String, transcriptHeading: String): String? {
        val parts = listOfNotNull(
            summary?.takeIf { it.isNotBlank() }?.let { summaryHeading to it.trim() },
            transcript?.takeIf { it.isNotBlank() }?.let { transcriptHeading to it.trim() },
        )
        return when (parts.size) {
            0 -> null
            1 -> parts.single().second
            else -> parts.joinToString("\n\n") { (heading, text) -> "$heading\n\n$text" }
        }
    }
}
