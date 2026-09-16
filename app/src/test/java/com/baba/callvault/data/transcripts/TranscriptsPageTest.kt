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
 * Plain JUnit, no Robolectric: this is a partition of one list, and the interesting failures — a
 * row under the wrong heading, or a page count that disagrees with the hub card the user has just
 * tapped — all render without complaint.
 */
class TranscriptsPageTest {

    private fun entry(name: String, state: TranscriptState) =
        TranscriptEntry(displayName = name, state = state, updatedAt = 0L)

    @Test
    fun each_state_lands_under_the_heading_that_describes_it() {
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("a.ogg", TranscriptState.DONE),
                entry("b.ogg", TranscriptState.QUEUED),
                entry("c.ogg", TranscriptState.RUNNING),
                entry("d.ogg", TranscriptState.FAILED),
            ),
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
        )

        // The hub card counts DONE rows; this is the number the page prints beside the same list.
        assertEquals(2, groups.ready.size)
    }

    @Test
    fun a_transcript_whose_recording_is_gone_is_still_listed() {
        // Dropping these looked tidier and immediately produced the defect this page exists to
        // avoid: the hub card counts DONE rows in the transcripts database and cannot see the
        // recordings catalog, so anything filtered out here is a number the user can catch the app
        // lying about simply by counting the rows under it.
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("deleted-months-ago.ogg", TranscriptState.DONE),
                entry("a.ogg", TranscriptState.DONE),
            ),
        )

        assertEquals(
            listOf("deleted-months-ago.ogg", "a.ogg"),
            groups.ready.map { it.displayName }
        )
    }

    @Test
    fun the_order_it_was_given_is_the_order_it_gives_back() {
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("c.ogg", TranscriptState.DONE),
                entry("a.ogg", TranscriptState.DONE),
                entry("b.ogg", TranscriptState.DONE),
            ),
        )

        assertEquals(listOf("c.ogg", "a.ogg", "b.ogg"), groups.ready.map { it.displayName })
    }

    @Test
    fun nothing_at_all_is_the_empty_page_and_a_queued_run_alone_is_not() {
        assertTrue(TranscriptsPage.group(emptyList()).isEmpty)

        val queuedOnly = TranscriptsPage.group(
            entries = listOf(entry("a.ogg", TranscriptState.QUEUED)),
        )
        // Nothing is readable yet, but the page has something to say — showing "nothing transcribed,
        // here is how to get one" over a transcription already in flight would be a lie.
        assertTrue(queuedOnly.ready.isEmpty())
        assertTrue(!queuedOnly.isEmpty)
    }

    // ---- the safety net under a transcribe-only import

    @Test
    fun a_transcribe_only_import_with_no_transcript_row_is_listed_as_waiting() {
        // The file exists on the phone and is deliberately kept out of Recordings. If it were not
        // listed here it would be in no list at all: audio the user could not find, play, retry or
        // delete. That is one step from having lost it.
        val groups = TranscriptsPage.group(
            entries = emptyList(),
            transcribeOnly = listOf("stopped.opus", "never-enqueued.opus"),
        )

        assertEquals(listOf("stopped.opus", "never-enqueued.opus"), groups.waiting)
        assertTrue(!groups.isEmpty)
    }

    @Test
    fun a_transcribe_only_import_that_is_already_accounted_for_is_not_listed_twice() {
        // Whatever its state, a transcript row already draws the file under one of the three
        // headings; adding it to waiting as well would make the page say two things about it.
        val groups = TranscriptsPage.group(
            entries = listOf(
                entry("queued.opus", TranscriptState.QUEUED),
                entry("running.opus", TranscriptState.RUNNING),
                entry("failed.opus", TranscriptState.FAILED),
                entry("done.opus", TranscriptState.DONE),
            ),
            transcribeOnly = listOf("queued.opus", "running.opus", "failed.opus", "done.opus", "loose.opus"),
        )

        assertEquals(listOf("loose.opus"), groups.waiting)
    }

    @Test
    fun a_library_with_no_transcribe_only_imports_is_unchanged() {
        val groups = TranscriptsPage.group(entries = listOf(entry("a.ogg", TranscriptState.DONE)))

        assertTrue(groups.waiting.isEmpty())
    }

}
