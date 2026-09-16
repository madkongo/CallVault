/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the Transcripts page divides what it is handed.
 *
 * Plain JUnit, no Robolectric: this is arithmetic over two lists, and the interesting failures —
 * a row under the wrong heading, an orphan drawn as an unlabelled row, a page count that disagrees
 * with the hub card the user just tapped — all render without complaint.
 */
class TranscriptsPageTest {

    private fun entry(name: String, state: TranscriptState) =
        TranscriptEntry(displayName = name, state = state, updatedAt = 0L)

    private val catalogue = setOf("a.ogg", "b.ogg", "c.ogg", "d.ogg", "e.ogg")

    @Test
    fun each_state_lands_under_the_heading_that_describes_it() {
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("a.ogg", TranscriptState.DONE),
                entry("b.ogg", TranscriptState.QUEUED),
                entry("c.ogg", TranscriptState.RUNNING),
                entry("d.ogg", TranscriptState.FAILED),
            ),
            catalogued = catalogue,
        )

        assertEquals(listOf("b.ogg", "c.ogg"), groups.working.map { it.displayName })
        assertEquals(listOf("d.ogg"), groups.failed.map { it.displayName })
        assertEquals(listOf("a.ogg"), groups.ready.map { it.displayName })
    }

    @Test
    fun only_finished_transcripts_are_readable_so_only_they_are_counted() {
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("a.ogg", TranscriptState.DONE),
                entry("b.ogg", TranscriptState.DONE),
                entry("c.ogg", TranscriptState.QUEUED),
                entry("d.ogg", TranscriptState.FAILED),
            ),
            catalogued = catalogue,
        )

        // The hub card counts DONE rows; this is the number the page prints beside the same list.
        assertEquals(2, groups.ready.size)
    }

    @Test
    fun a_transcript_whose_recording_is_gone_is_dropped_from_every_group() {
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("deleted-months-ago.ogg", TranscriptState.DONE),
                entry("also-gone.ogg", TranscriptState.FAILED),
                entry("a.ogg", TranscriptState.DONE),
            ),
            catalogued = catalogue,
        )

        assertEquals(listOf("a.ogg"), groups.ready.map { it.displayName })
        assertTrue(groups.failed.isEmpty())
    }

    @Test
    fun the_order_it_was_given_is_the_order_it_gives_back() {
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("c.ogg", TranscriptState.DONE),
                entry("a.ogg", TranscriptState.DONE),
                entry("b.ogg", TranscriptState.DONE),
            ),
            catalogued = catalogue,
        )

        assertEquals(listOf("c.ogg", "a.ogg", "b.ogg"), groups.ready.map { it.displayName })
    }

    @Test
    fun nothing_at_all_is_the_empty_page_and_a_queued_run_alone_is_not() {
        assertTrue(TranscriptsPage.group(emptyList(), catalogue).isEmpty)

        val queuedOnly = TranscriptsPage.group(
            entries = listOf(entry("a.ogg", TranscriptState.QUEUED)),
            catalogued = catalogue,
        )
        // Nothing is readable yet, but the page has something to say — showing "nothing transcribed,
        // here is how to get one" over a transcription already in flight would be a lie.
        assertTrue(queuedOnly.ready.isEmpty())
        assertTrue(!queuedOnly.isEmpty)
    }

    @Test
    fun an_empty_catalogue_drops_everything_rather_than_listing_rows_that_open_onto_nothing() {
        val groups = TranscriptsPage.group(
            entries = listOf(entry("a.ogg", TranscriptState.DONE)),
            catalogued = emptySet(),
        )

        assertTrue(groups.isEmpty)
    }
}
