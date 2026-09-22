/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.baba.callvault.data.transcripts.PageSearch
import com.baba.callvault.data.transcripts.TranscriptRepository
import kotlinx.coroutines.delay

/**
 * The word search of one library page: what is typed, and what the page's index found for it.
 *
 * @param excerpts one per recording found, by display name; empty for a blank query. It lags the
 *   query by the debounce and the query itself, so a page filtering on it while the query is not
 *   blank may show the previous result for a moment — the rows narrow, they never flash empty.
 */
class PageSearchState(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val excerpts: Map<String, PageSearch.Excerpt>,
) {
    /** Whether the page is showing a search rather than its whole list. */
    val isActive: Boolean get() = query.isNotBlank()
}

/** Typing is bursty; one query per pause rather than one per keystroke, the same figure the old sheet used. */
private const val SEARCH_DEBOUNCE_MS = 250L

/**
 * The search state for [index], surviving rotation, re-run when the words change under it.
 *
 * @param version anything that changes when the index's content does (the page's own entries), so a
 *   transcript finishing while a search is open joins the result without retyping.
 */
@Composable
fun rememberPageSearch(index: TranscriptRepository.PageIndex, version: Any?): PageSearchState {
    val context = LocalContext.current
    var query by rememberSaveable(index) { mutableStateOf("") }
    val excerpts by produceState<Map<String, PageSearch.Excerpt>>(emptyMap(), query, version) {
        if (query.isBlank()) {
            value = emptyMap()
            return@produceState
        }
        delay(SEARCH_DEBOUNCE_MS)
        value = TranscriptRepository.searchPage(context, index, query)
    }
    return PageSearchState(query = query, onQueryChange = { query = it }, excerpts = excerpts)
}
