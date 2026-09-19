/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.baba.callvault.R

/**
 * What the post-update note tells the user, newest release first.
 *
 * One entry per release rather than one per feature. Someone who skips a few updates should still find
 * out what changed, so the note shows the last [MAX_SHOWN] releases together, each labelled with its
 * version, instead of introducing a single feature and silently dropping the rest.
 *
 * Keep this list in sync with `CHANGELOG.md` — same releases, plain language. Add new entries at the
 * top; older ones fall off the end on their own.
 */
data class ReleaseHighlight(
    val version: String,
    @StringRes val title: Int,
    /**
     * One line per change, as a string-array.
     *
     * An array rather than one string with line breaks in it: raw newlines inside a string resource
     * are collapsed to spaces by the resource compiler, which turned nine changes into a single
     * paragraph on the first attempt at this — the very wall of text the list replaced. An array
     * cannot be flattened by accident, and it is what translators expect to see for a list.
     *
     * Null for the older releases: only the newest is written out, the rest show their headline.
     */
    @ArrayRes val items: Int? = null,
    /** Where to find it, when the feature is off by default and needs switching on. */
    @StringRes val whereToFind: Int? = null,
)

object ReleaseHighlights {

    /** How many releases the note shows. Older entries stay listed here but are not displayed. */
    const val MAX_SHOWN = 3

    private val ALL = listOf(
        ReleaseHighlight(
            version = "2.4.0",
            title = R.string.whatsnew_240_title,
            items = R.array.whatsnew_240_items,
            // Import is the one thing here someone would go looking for and not find: it lives on the
            // Transcripts page rather than in Settings, and nothing else points at it.
            whereToFind = R.string.whatsnew_240_where,
        ),
        ReleaseHighlight(
            version = "2.3.0",
            title = R.string.whatsnew_230_title,
            items = R.array.whatsnew_230_items,
            // Merging is reached from a recording's own menu rather than a settings screen, so the
            // pointer names where the action lives, not something to switch on.
            whereToFind = R.string.whatsnew_230_where,
        ),
        ReleaseHighlight(
            version = "2.2.1",
            title = R.string.whatsnew_221_title,
            // A maintenance release: everything in it applies on its own and none of it is a setting.
            // A pointer here would have to name a screen where there is nothing to do.
            whereToFind = null,
        ),
        ReleaseHighlight(
            version = "2.2.0",
            title = R.string.whatsnew_220_title,
            // Most of this release applies on its own, but the housekeeping options are off by
            // default and are the ones someone would go looking for, so the pointer names them.
            whereToFind = R.string.whatsnew_220_where,
        ),
        ReleaseHighlight(
            version = "2.1.1",
            title = R.string.whatsnew_211_title,
            // Nothing to switch on and nowhere to go — the improvement applies to every transcription
            // and summary from here on. Pointing at a screen would be pointing at nothing.
            whereToFind = null,
        ),
        ReleaseHighlight(
            version = "2.1.0",
            title = R.string.whatsnew_210_title,
            // Not a setting to switch on, so this says when it applies rather than where it lives:
            // the labels appear on calls recorded from this version onwards and on no others.
            whereToFind = R.string.whatsnew_210_where,
        ),
        ReleaseHighlight(
            version = "2.0.0",
            title = R.string.whatsnew_200_title,
            // This is the only place someone finds out the feature exists, and it costs a 3.5 GB
            // download — so the note says where to start rather than leaving them to go looking.
            whereToFind = R.string.whatsnew_200_where,
        ),
        ReleaseHighlight(
            version = "1.5.7",
            title = R.string.whatsnew_157_title,
            whereToFind = R.string.whatsnew_157_where,
        ),
        ReleaseHighlight(
            version = "1.5.6",
            title = R.string.whatsnew_156_title,
            whereToFind = R.string.whatsnew_156_where,
        ),
        ReleaseHighlight(
            version = "1.5.5",
            title = R.string.whatsnew_155_title,
        ),
        ReleaseHighlight(
            version = "1.5.4",
            title = R.string.whatsnew_154_title,
        ),
        ReleaseHighlight(
            version = "1.5.3",
            title = R.string.whatsnew_153_title,
        ),
        ReleaseHighlight(
            version = "1.5.0",
            title = R.string.whatsnew_150_title,
            whereToFind = R.string.whatsnew_150_where,
        ),
        ReleaseHighlight(
            version = "1.4.8",
            title = R.string.whatsnew_148_title,
        ),
        ReleaseHighlight(
            version = "1.4.7",
            title = R.string.whatsnew_147_title,
            whereToFind = R.string.whatsnew_147_where,
        ),
        ReleaseHighlight(
            version = "1.4.6",
            title = R.string.whatsnew_146_title,
            whereToFind = R.string.whatsnew_146_where,
        ),
        ReleaseHighlight(
            version = "1.4.5",
            title = R.string.whatsnew_145_title,
        ),
    )

    /** The releases to show in the post-update note, newest first. */
    fun recent(): List<ReleaseHighlight> = ALL.take(MAX_SHOWN)
}
