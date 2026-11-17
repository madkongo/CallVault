/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

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
}
