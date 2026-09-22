/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search line on the recordings page: what is typed narrows the list by contact, and the names
 * it offers to complete come from the recordings themselves, never from the phone's contact book.
 *
 * Robolectric only because [RecordingItem] carries a [Uri]; everything here is a pure function of state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeContactSearchTest {

    private val recordings = listOf(
        item("1.m4a", contactName = "Dana Cohen"),
        item("2.m4a", contactName = "דנה לוי"),
        item("3.m4a", contactName = null, number = "+972501234567"),
        item("4.m4a", contactName = "Daniel"),
    )

    @Test
    fun `an empty query leaves the list alone`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "")

        assertEquals(recordings, state.filteredRecordings)
    }

    @Test
    fun `typing narrows the list to contacts whose name contains it, in any case`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "dan")

        assertEquals(listOf("1.m4a", "4.m4a"), state.filteredRecordings.map { it.displayName })
    }

    @Test
    fun `hebrew is matched as typed`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "דנה")

        assertEquals(listOf("2.m4a"), state.filteredRecordings.map { it.displayName })
    }

    @Test
    fun `a recording with no contact is found by its number`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "50123")

        assertEquals(listOf("3.m4a"), state.filteredRecordings.map { it.displayName })
    }

    @Test
    fun `spaces around the query do not count`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "  Daniel ")

        assertEquals(listOf("4.m4a"), state.filteredRecordings.map { it.displayName })
    }

    @Test
    fun `the completions are the contacts with recordings that match, A to Z`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "d")

        assertEquals(listOf("Dana Cohen", "Daniel"), state.contactSuggestions)
    }

    @Test
    fun `only names are offered, never a number or a file name standing in for one`() {
        // A recording with no contact is listed under its number, or failing that its file name.
        // Typing finds it either way; a completion menu of file names is not what "contact" means.
        val mixed = recordings + item("5.m4a", contactName = null, number = null)

        assertEquals(emptyList<String>(), HomeViewModel.HomeUiState(recordings = mixed, contactQuery = "5").contactSuggestions)
        assertEquals(emptyList<String>(), HomeViewModel.HomeUiState(recordings = mixed, contactQuery = "972").contactSuggestions)
    }

    @Test
    fun `a name the recorder wrote where the number goes is offered like any other name`() {
        // The OP9's Shizuku recordings: `20260920_122553…_out_פרוזה.ogg`. The recorder had the name and
        // not the number, so the parser's number field holds the name and the contact lookup finds
        // nothing. The row is titled with it, the filter matched 44 rows on it — and no menu offered it.
        val namedInNumberSlot = listOf(item("6.m4a", contactName = null, number = "פרוזה"))

        assertEquals(listOf("פרוזה"), HomeViewModel.HomeUiState(recordings = namedInNumberSlot, contactQuery = "פ").contactSuggestions)
    }

    @Test
    fun `nothing is offered for an empty query or one already completed`() {
        assertEquals(emptyList<String>(), HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "").contactSuggestions)
        assertEquals(emptyList<String>(), HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "Daniel").contactSuggestions)
    }

    @Test
    fun `the completions stop at a handful`() {
        val many = (1..20).map { item("$it.m4a", contactName = "Contact $it") }
        val state = HomeViewModel.HomeUiState(recordings = many, contactQuery = "contact")

        assertEquals(HomeViewModel.HomeUiState.MAX_CONTACT_SUGGESTIONS, state.contactSuggestions.size)
    }

    @Test
    fun `the query combines with the other facets rather than replacing them`() {
        val state = HomeViewModel.HomeUiState(
            recordings = recordings,
            contactQuery = "dan",
            favouritesOnly = true,
            favourites = setOf("4.m4a"),
        )

        assertEquals(listOf("4.m4a"), state.filteredRecordings.map { it.displayName })
    }

    private fun item(name: String, contactName: String?, number: String? = null) = RecordingItem(
        uri = Uri.parse("content://test/$name"),
        displayName = name,
        sizeBytes = 1_000L,
        lastModified = 0L,
        direction = null,
        displayDate = null,
        startedAtMillis = null,
        number = number,
        contactName = contactName,
    )
}
