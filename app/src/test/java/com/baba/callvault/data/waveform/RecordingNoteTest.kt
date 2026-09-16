/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.waveform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The note, for a recording that is no longer there.
 *
 * The property the reading view's note editor rests on: a note is keyed by the **display name** and
 * by nothing else, so it can be written and read for a transcript whose recording has gone. Until the
 * editor existed there was no way to reach such a note — notes live on the playback screen, and an
 * orphaned transcript has none — so nothing would have caught this being keyed on a catalog row
 * instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class RecordingNoteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A name that is deliberately in no catalog: what a finished "Transcribe only" import becomes. */
    private val orphan = "20260916_101010.123+0300_import_transcribeonly_voice note.opus"

    @Test
    fun a_note_can_be_written_and_read_for_a_recording_the_catalog_does_not_know() = runBlocking {
        assertEquals(emptyList<String>(), RecordingCatalog.all(context).map { it.displayName })

        RecordingExtrasRepository.saveNote(context, orphan, "chase this on Monday")

        assertEquals("chase this on Monday", RecordingExtrasRepository.note(context, orphan).first())
    }

    @Test
    fun emptying_a_note_removes_it_rather_than_storing_a_blank() = runBlocking {
        RecordingExtrasRepository.saveNote(context, orphan, "something")
        RecordingExtrasRepository.saveNote(context, orphan, "   ")

        // Empty, so the reading view's button offers to *add* one again rather than claiming there
        // is a note to read.
        assertEquals("", RecordingExtrasRepository.note(context, orphan).first())
    }
}
