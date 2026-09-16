/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A multi-selection that cannot cross between two library pages.
 *
 * Worth pinning because the failure is silent and the next tap is a delete: Transcripts and
 * Summaries are two lists of display names with a large overlap, so a set carried from one to the
 * other arrives looking like a perfectly valid selection of different things.
 */
class LibrarySelectionTest {

    @Test
    fun nothing_is_selected_to_begin_with() {
        assertTrue(LibrarySelection.EMPTY.names.isEmpty())
        assertNull(LibrarySelection.EMPTY.section)
        assertTrue(LibrarySelection.EMPTY.on(HomeSection.Transcripts).isEmpty())
    }

    @Test
    fun a_row_picked_on_transcripts_is_selected_there() {
        val selection = LibrarySelection.EMPTY.toggled(HomeSection.Transcripts, "a.ogg")
        assertEquals(setOf("a.ogg"), selection.on(HomeSection.Transcripts))
    }

    @Test
    fun a_row_picked_on_transcripts_is_not_selected_on_summaries() {
        // The leak this type exists to stop. The same call is usually in both lists, so the names
        // would resolve and the bulk delete would run — against the wrong page's meaning of Delete.
        val selection = LibrarySelection.EMPTY.toggled(HomeSection.Transcripts, "a.ogg")
        assertTrue(selection.on(HomeSection.Summaries).isEmpty())
    }

    @Test
    fun picking_on_another_page_replaces_the_selection_rather_than_adding_to_it() {
        val selection = LibrarySelection.EMPTY
            .toggled(HomeSection.Transcripts, "a.ogg")
            .toggled(HomeSection.Transcripts, "b.ogg")
            .toggled(HomeSection.Summaries, "c.ogg")

        assertEquals(setOf("c.ogg"), selection.on(HomeSection.Summaries))
        assertTrue(selection.on(HomeSection.Transcripts).isEmpty())
    }

    @Test
    fun tapping_a_selected_row_again_takes_it_out() {
        val selection = LibrarySelection.EMPTY
            .toggled(HomeSection.Transcripts, "a.ogg")
            .toggled(HomeSection.Transcripts, "b.ogg")
            .toggled(HomeSection.Transcripts, "a.ogg")

        assertEquals(setOf("b.ogg"), selection.on(HomeSection.Transcripts))
    }

    @Test
    fun deselecting_the_last_row_leaves_selection_mode_entirely() {
        // One representation of "nothing selected", so selection mode ends the same way wherever it
        // ends — including when it ends by un-picking rather than by backing out.
        val selection = LibrarySelection.EMPTY
            .toggled(HomeSection.Transcripts, "a.ogg")
            .toggled(HomeSection.Transcripts, "a.ogg")

        assertEquals(LibrarySelection.EMPTY, selection)
        assertNull(selection.section)
    }

    @Test
    fun a_name_that_is_in_both_libraries_is_still_only_selected_where_it_was_picked() {
        // The concrete shape of the overlap: most summarised calls are transcribed ones.
        val shared = "20260916_120000.000+0300_Dad.ogg"
        val selection = LibrarySelection.EMPTY.toggled(HomeSection.Summaries, shared)

        assertTrue(selection.on(HomeSection.Summaries).contains(shared))
        assertFalse(selection.on(HomeSection.Transcripts).contains(shared))
        assertFalse(selection.on(HomeSection.Recordings).contains(shared))
    }
}
