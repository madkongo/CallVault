/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

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
    fun a_file_from_before_notes_existed_is_saved_under_a_generic_name() {
        // voarch's 13 minutes are in such a file: written by 2.4.1, with no note beside it.
        assertEquals(RescueDecision.SAVE_UNNAMED, decide(hasNote = false))
    }

    @Test
    fun an_empty_file_holds_no_audio_and_is_only_cleaned_up() {
        assertEquals(RescueDecision.DISCARD_EMPTY, decide(sizeBytes = 0))
        assertEquals(RescueDecision.DISCARD_EMPTY, decide(hasNote = false, sizeBytes = 0))
    }

    @Test
    fun the_container_is_recognised_from_its_first_bytes() {
        assertEquals(StagedContainer.OGG, StagedContainer.sniff("OggS".toByteArray() + ByteArray(8)))
        val mp4 = ByteArray(4) + "ftypM4A ".toByteArray()
        assertEquals(StagedContainer.MP4, StagedContainer.sniff(mp4))
        assertEquals(StagedContainer.UNKNOWN, StagedContainer.sniff(ByteArray(12)))
        assertEquals(StagedContainer.UNKNOWN, StagedContainer.sniff(ByteArray(2)))
    }

    @Test
    fun a_generic_name_carries_the_time_and_the_right_extension() {
        assertEquals("recovered_20260928_1226.ogg", CutOffRescuePolicy.unnamedFileName("20260928_1226", StagedContainer.OGG))
        assertEquals("recovered_20260928_1226.m4a", CutOffRescuePolicy.unnamedFileName("20260928_1226", StagedContainer.MP4))
    }

    private fun decide(
        hasNote: Boolean = true,
        sizeBytes: Long = 900_000,
        ageMs: Long = CutOffRescuePolicy.QUIET_MS,
        callActive: Boolean = false,
    ) = CutOffRescuePolicy.decide(hasNote = hasNote, sizeBytes = sizeBytes, ageMs = ageMs, callActive = callActive)
}
