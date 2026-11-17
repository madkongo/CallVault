/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

/**
 * Which card the Recordings list shows when it has no rows.
 *
 * Three different truths hide behind an empty list, and only one of them is "no recordings yet":
 * the library really is empty, the typed search found nothing, or the filters leave nothing. Showing
 * the first for the other two tells someone with sixty calls that they have none.
 */
enum class RecordingsEmptyState {
    /** Rows are listed; no empty card. */
    NONE,

    /** The library holds no recordings at all. */
    LIBRARY_EMPTY,

    /** Something is typed in the search line and nothing matches it. */
    NO_SEARCH_MATCH,

    /** Chips (source, direction, date, tag, starred) leave nothing, with no search typed. */
    NO_FILTER_MATCH;

    companion object {
        /**
         * @param totalRecordings every recording, before any search or filter.
         * @param listedRecordings what the list shows after them.
         * @param searchTyped whether the search line holds something.
         * @param filtersActive whether any chip filter is on.
         *
         * A typed search wins over active filters: it is what the user just did, and clearing it is
         * the first thing to offer.
         */
        fun of(totalRecordings: Int, listedRecordings: Int, searchTyped: Boolean, filtersActive: Boolean): RecordingsEmptyState = when {
            listedRecordings > 0 -> NONE
            totalRecordings == 0 -> LIBRARY_EMPTY
            searchTyped -> NO_SEARCH_MATCH
            filtersActive -> NO_FILTER_MATCH
            else -> LIBRARY_EMPTY
        }
    }
}
