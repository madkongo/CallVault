/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.db.RecordingLabelEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cached name and date of a recording: what is written when the list loads, and what is not.
 *
 * Robolectric only because [RecordingItem] carries a [Uri].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryLabelsTest {

    @Test
    fun `a recording is labelled the way its row is titled`() {
        assertEquals(
            RecordingLabelEntry("a.m4a", "Dana", "20/09/26 12:25"),
            LibraryLabels.of(item("a.m4a", contactName = "Dana", number = "+972501234567", date = "20/09/26 12:25")),
        )
        assertEquals(
            RecordingLabelEntry("b.m4a", "+972501234567", null),
            LibraryLabels.of(item("b.m4a", contactName = null, number = "+972501234567", date = null)),
        )
        assertEquals(
            RecordingLabelEntry("c.m4a", "c.m4a", null),
            LibraryLabels.of(item("c.m4a", contactName = null, number = null, date = null)),
        )
    }

    @Test
    fun `only labels that are new or have changed are written`() {
        // The list reloads after every call; rewriting thousands of unchanged rows each time would
        // be a database transaction for nothing, on every reload, for ever.
        val stored = listOf(
            RecordingLabelEntry("a.m4a", "Dana", "20/09/26 12:25"),
            RecordingLabelEntry("b.m4a", "Old Name", "19/09/26 20:47"),
        )
        val loaded = listOf(
            item("a.m4a", contactName = "Dana", number = null, date = "20/09/26 12:25"),
            item("b.m4a", contactName = "New Name", number = null, date = "19/09/26 20:47"),
            item("c.m4a", contactName = "Third", number = null, date = "18/09/26 09:00"),
        )

        val toWrite = LibraryLabels.changed(stored, loaded)

        assertEquals(listOf("b.m4a", "c.m4a"), toWrite.map { it.displayName })
        assertEquals("New Name", toWrite[0].label)
    }

    @Test
    fun `a recording that is gone keeps its label`() {
        // The label outlives the audio on purpose: an orphaned transcript is the very row that has
        // nothing else to be named by.
        val stored = listOf(RecordingLabelEntry("gone.m4a", "Dana", null))

        assertEquals(emptyList<RecordingLabelEntry>(), LibraryLabels.changed(stored, emptyList()))
    }

    private fun item(name: String, contactName: String?, number: String?, date: String?) = RecordingItem(
        uri = Uri.parse("content://test/$name"),
        displayName = name,
        sizeBytes = 1_000L,
        lastModified = 0L,
        direction = null,
        displayDate = date,
        startedAtMillis = null,
        number = number,
        contactName = contactName,
    )
}
