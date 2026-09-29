/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.data.health.CallLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Saving the part of a call that was recorded before the phone killed CallVault.
 *
 * voarch (OnePlus, 2026-09-28): the app was killed 13 minutes into a call, the recording died with it, and
 * the staged file sat in private storage where nobody could reach it. The rescue must never touch a
 * recording that is still being made, and must never delete anything that holds audio.
 */
class CutOffRescueTest {

    private val note = StagingNote(
        folderUri = "content://com.android.externalstorage.documents/tree/primary%3Acallvault",
        fileName = "20260928_122607.511+0200_in_Anne=Frank.ogg",
        mimeType = "audio/ogg",
        startedAtMillis = 1_790_000_000_000L,
    )

    @Test
    fun a_note_survives_a_round_trip_even_with_an_equals_sign_in_the_name() {
        assertEquals(note, StagingNote.decode(note.encode()))
    }

    @Test
    fun a_damaged_note_reads_as_no_note_rather_than_a_wrong_one() {
        assertNull(StagingNote.decode("v=1\nfolder=content://x\n"))
        assertNull(StagingNote.decode(""))
        assertNull(StagingNote.decode("v=1\nfolder=x\nname=a.ogg\nmime=audio/ogg\nstarted=soon"))
    }

    @Test
    fun nothing_is_touched_while_a_call_is_up() {
        // The recorder host can still be writing the file after the app died — that is how voarch's app
        // restarted at 12:39 while his call ran on to 12:45.
        assertEquals(RescueDecision.WAIT, decide(callActive = true))
    }

    @Test
    fun a_file_written_moments_ago_is_left_alone() {
        assertEquals(RescueDecision.WAIT, decide(ageMs = CutOffRescuePolicy.QUIET_MS - 1))
    }

    @Test
    fun a_quiet_cut_off_recording_with_a_note_is_saved_under_its_own_name() {
        assertEquals(RescueDecision.SAVE_NAMED, decide())
    }

    @Test
    fun a_file_with_no_note_is_left_alone_never_reported() {
        // A no-note temp is unclassifiable — a real cut-off from an old build OR a normally-finished
        // call's orphaned temp. Reporting it "cut off" cried wolf on a pre-2.4.4 leftover (OP12,
        // 2026-09-29). So it is left in place, never published, never notified.
        assertEquals(RescueDecision.LEAVE_UNATTRIBUTED, decide(hasNote = false))
    }

    @Test
    fun an_empty_note_bearing_file_holds_no_audio_and_is_cleaned_up() {
        assertEquals(RescueDecision.DISCARD_EMPTY, decide(sizeBytes = 0))
        // A no-note file is left alone even when empty — we never touch what we cannot attribute.
        assertEquals(RescueDecision.LEAVE_UNATTRIBUTED, decide(hasNote = false, sizeBytes = 0))
    }



    // Which call the rescued recording belongs to, so the "call was not recorded" warning for it can be
    // replaced by the truth. voarch: call-log start 12:26:04, recording started 12:26:07, cut at 12:39.

    private val minute = 60_000L
    private val callStart = 1_000_000_000L
    private val theCall = CallLogEntry(startedAt = callStart, durationSeconds = 19 * 60, isIncoming = true, label = "Anne")
    private val earlierCall = CallLogEntry(startedAt = callStart - 60 * minute, durationSeconds = 120, isIncoming = false, label = "Bob")

    @Test
    fun the_call_that_was_up_when_the_recording_stopped_is_the_one_it_belongs_to() {
        val match = CutOffRescuePolicy.matchCall(listOf(theCall, earlierCall), recordingStartedAt = callStart + 3_000, cutAt = callStart + 13 * minute)
        assertEquals(theCall, match)
    }

    @Test
    fun a_file_without_a_note_is_matched_by_the_moment_it_was_cut_off() {
        assertEquals(theCall, CutOffRescuePolicy.matchCall(listOf(theCall, earlierCall), recordingStartedAt = null, cutAt = callStart + 13 * minute))
    }

    @Test
    fun no_call_matches_when_none_was_up_at_the_cut() {
        assertNull(CutOffRescuePolicy.matchCall(listOf(earlierCall), recordingStartedAt = null, cutAt = callStart + 13 * minute))
    }

    @Test
    fun a_call_that_started_long_before_the_recording_is_not_claimed() {
        // A long earlier call still "up" by duration must not be taken for this recording's call.
        val longEarlier = earlierCall.copy(durationSeconds = 90 * 60)
        assertNull(CutOffRescuePolicy.matchCall(listOf(longEarlier), recordingStartedAt = callStart + 3_000, cutAt = callStart + 13 * minute))
    }

    private fun decide(
        hasNote: Boolean = true,
        sizeBytes: Long = 900_000,
        ageMs: Long = CutOffRescuePolicy.QUIET_MS,
        callActive: Boolean = false,
    ) = CutOffRescuePolicy.decide(hasNote = hasNote, sizeBytes = sizeBytes, ageMs = ageMs, callActive = callActive)
}
