/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.data.transcripts.export.TranscriptFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a library row's menu offers, and what its Delete destroys.
 *
 * Pinned because every wrong answer here renders perfectly: a Share on a row with no words is a tap
 * that does nothing, and a Delete whose confirmation describes the wrong thing is a user agreeing to
 * something else. Neither crashes, neither logs.
 */
class LibraryRowActionsTest {

    @Test
    fun a_finished_transcript_may_be_shared_saved_and_deleted() {
        val menu = LibraryRowActions.forTranscript(TranscriptState.DONE, hasAudio = true)
        assertTrue(menu.share)
        assertTrue(menu.save)
        assertEquals(LibraryRowActions.DeleteMeaning.TranscriptKeepingAudio, menu.delete)
    }

    @Test
    fun deleting_a_transcript_that_still_has_its_recording_takes_only_the_words() {
        // The decision the maintainer asked to have stated: the Transcripts page lists text, and the
        // recording stays reachable from Recordings, where deleting one already has its own
        // confirmation naming the copies it would take.
        assertEquals(
            LibraryRowActions.DeleteMeaning.TranscriptKeepingAudio,
            LibraryRowActions.forTranscript(TranscriptState.DONE, hasAudio = true).delete
        )
    }

    @Test
    fun deleting_a_transcript_with_no_recording_is_deleting_the_whole_item() {
        // Every finished "Transcribe only" import is this row, so it is the ordinary case rather than
        // the exotic one — and the reassuring sentence about a recording being kept would be a lie
        // told to somebody about to lose the only record of a conversation.
        assertEquals(
            LibraryRowActions.DeleteMeaning.TranscriptAndNothingLeft,
            LibraryRowActions.forTranscript(TranscriptState.DONE, hasAudio = false).delete
        )
    }

    @Test
    fun a_transcription_still_running_offers_nothing() {
        // No words yet, so Share and Save would be taps that do nothing — and a second way to make a
        // running transcription disappear, spelled "Delete", would be the confusing one. Stop is on
        // the heading above it.
        listOf(TranscriptState.QUEUED, TranscriptState.RUNNING).forEach { state ->
            val menu = LibraryRowActions.forTranscript(state, hasAudio = true)
            assertTrue("$state should offer nothing", menu.isEmpty)
        }
    }

    @Test
    fun a_failed_transcription_offers_nothing() {
        // It never produced any words. Its row already carries the one action worth having: retry.
        assertTrue(LibraryRowActions.forTranscript(TranscriptState.FAILED, hasAudio = true).isEmpty)
    }

    @Test
    fun deleting_a_summary_takes_the_summary() {
        val menu = LibraryRowActions.forStoredSummary()
        assertTrue(menu.share)
        assertTrue(menu.save)
        assertEquals(LibraryRowActions.DeleteMeaning.Summary, menu.delete)
    }

    @Test
    fun an_empty_menu_knows_it_is_empty() {
        assertTrue(LibraryRowActions.NONE.isEmpty)
        assertNull(LibraryRowActions.NONE.delete)
        assertFalse(LibraryRowActions.NONE.share)
    }

    @Test
    fun transcripts_save_in_every_format_the_reading_view_offers() {
        assertEquals(
            TranscriptFormat.entries.toList(),
            LibraryRowActions.formatsFor(LibraryRowActions.Page.Transcripts)
        )
    }

    @Test
    fun summaries_save_only_in_the_formats_that_can_carry_a_summary() {
        // SRT and VTT are subtitle files and TXT is the transcript as it reads on screen: all three
        // would write a file with no summary in it at all, from a menu item called Save on a page
        // about summaries. Silently dropping the thing being saved is the defect this prevents.
        assertEquals(
            listOf(TranscriptFormat.MARKDOWN, TranscriptFormat.JSON),
            LibraryRowActions.formatsFor(LibraryRowActions.Page.Summaries)
        )
    }

    @Test
    fun a_bulk_delete_of_transcripts_that_all_have_recordings_says_the_recordings_are_kept() {
        assertEquals(
            LibraryRowActions.BulkPrompt.TranscriptsAudioKept,
            LibraryRowActions.bulkPrompt(LibraryRowActions.Page.Transcripts, textOnlyCount = 0)
        )
    }

    @Test
    fun one_text_only_row_in_a_large_selection_changes_what_the_bulk_delete_says() {
        // The mixed case, decided by its worst member rather than by its majority: "the recordings
        // are kept" is false for something in this batch, and being true of thirty-nine others does
        // not make it safe to say.
        assertEquals(
            LibraryRowActions.BulkPrompt.TranscriptsSomeAllThatIsLeft,
            LibraryRowActions.bulkPrompt(LibraryRowActions.Page.Transcripts, textOnlyCount = 1)
        )
    }

    @Test
    fun a_bulk_delete_of_summaries_says_the_same_thing_whatever_is_in_the_selection() {
        // Deleting a summary never touches audio, so whether the audio is there is not part of what
        // the user is being asked to agree to.
        assertEquals(
            LibraryRowActions.BulkPrompt.Summaries,
            LibraryRowActions.bulkPrompt(LibraryRowActions.Page.Summaries, textOnlyCount = 0)
        )
        assertEquals(
            LibraryRowActions.BulkPrompt.Summaries,
            LibraryRowActions.bulkPrompt(LibraryRowActions.Page.Summaries, textOnlyCount = 3)
        )
    }
}
