/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import com.baba.callvault.ui.viewmodels.HomeViewModel.DirectionFilter
import com.baba.callvault.ui.viewmodels.HomeViewModel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which card an empty Recordings list shows: only a really empty library says "No recordings yet". */
class RecordingsEmptyStateTest {

    private fun of(total: Int, listed: Int, search: Boolean = false, filters: Boolean = false) =
        RecordingsEmptyState.of(total, listed, search, filters)

    @Test
    fun `rows listed means no empty card`() {
        assertEquals(RecordingsEmptyState.NONE, of(total = 5, listed = 2, search = true, filters = true))
    }

    @Test
    fun `an empty library is the no-recordings card`() {
        assertEquals(RecordingsEmptyState.LIBRARY_EMPTY, of(total = 0, listed = 0))
        // Even with a search typed: there is nothing to match against.
        assertEquals(RecordingsEmptyState.LIBRARY_EMPTY, of(total = 0, listed = 0, search = true))
    }

    @Test
    fun `a search that finds nothing in a full library is no-matches, not no-recordings`() {
        assertEquals(RecordingsEmptyState.NO_SEARCH_MATCH, of(total = 60, listed = 0, search = true))
    }

    @Test
    fun `filters that leave nothing are their own card`() {
        assertEquals(RecordingsEmptyState.NO_FILTER_MATCH, of(total = 60, listed = 0, filters = true))
    }

    @Test
    fun `a typed search wins over active filters`() {
        assertEquals(RecordingsEmptyState.NO_SEARCH_MATCH, of(total = 60, listed = 0, search = true, filters = true))
    }

    @Test
    fun `state wires the query and the filters through`() {
        assertEquals(
            RecordingsEmptyState.NO_SEARCH_MATCH,
            HomeUiState(contactQuery = "zzz", recordings = listOf()).let {
                // No recordings in this state, so the library card; the wiring is proven below.
                assertEquals(RecordingsEmptyState.LIBRARY_EMPTY, it.emptyState)
                of(total = 3, listed = 0, search = it.contactQuery.isNotBlank(), filters = it.hasActiveFilters)
            }
        )
        assertFalse(HomeUiState().hasActiveFilters)
        assertTrue(HomeUiState(directionFilter = DirectionFilter.INCOMING).hasActiveFilters)
        assertTrue(HomeUiState(favouritesOnly = true).hasActiveFilters)
        assertTrue(HomeUiState(tagFilter = "work").hasActiveFilters)
        assertTrue(HomeUiState(dateFilter = "20261101").hasActiveFilters)
    }
}
