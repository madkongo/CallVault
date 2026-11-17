/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.summary.CallSummary

/**
 * When the recording's screen folds a block of text to a few lines and a "more".
 *
 * Decided from the text itself rather than from a layout measurement: a measurement would flip the
 * answer while someone types in a note, and a note that collapses under the cursor is worse than one
 * that is long. A short note is never folded, so it never grows a "more" it does not need.
 */
object CollapsePolicy {

    /** Lines shown while folded. */
    const val COLLAPSED_LINES = 3

    /** Past this many characters a block is long even if it has no line breaks. */
    const val LONG_CHARS = 160

    /** True when [text] is long enough to start folded. */
    fun isLong(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length > LONG_CHARS || trimmed.lines().size > COLLAPSED_LINES
    }

    /** A summary with more bullet points than this (decisions, to-dos, key points, facts) is long. */
    const val SHORT_SUMMARY_ITEMS = 3

    /**
     * True when [summary] should start folded. A short one — brief prose and only a few points — is
     * shown whole: a "More" that reveals two more lines is a button for nothing.
     */
    fun isLongSummary(summary: CallSummary): Boolean {
        val items = summary.decisions.size + summary.actionItems.size +
            summary.keyPoints.size + summary.keyFacts.size
        return isLong(summary.intent + "\n" + summary.summary) || items > SHORT_SUMMARY_ITEMS
    }
}
