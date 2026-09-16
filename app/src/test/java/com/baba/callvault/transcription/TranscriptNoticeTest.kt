/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the shade says as transcriptions finish.
 *
 * The decision is tested and the posting is not: the whole risk here is *how many* notifications a
 * batch produces, and that is settled entirely by this merge — the nightly sweep can finish fifty
 * recordings in one run.
 */
class TranscriptNoticeTest {

    @Test
    fun `the first finish names the recording`() {
        val shade = TranscriptNotice.after(previous = null, displayName = "a.ogg")

        assertEquals(1, shade.count)
        assertEquals("a.ogg", shade.displayName)
        assertFalse(shade.audioDeleted)
    }

    @Test
    fun `the first finish remembers that the audio went`() {
        // The "Transcribe only" case, and the one the notification exists for most: it is the app's
        // only account of what became of a file that is no longer on the phone.
        val shade = TranscriptNotice.after(null, "note.opus", audioDeleted = true)

        assertEquals("note.opus", shade.displayName)
        assert(shade.audioDeleted)
    }

    @Test
    fun `a second finish stops naming anything`() {
        val first = TranscriptNotice.after(null, "a.ogg")
        val second = TranscriptNotice.after(first, "b.ogg")

        assertEquals(2, second.count)
        // Naming the last of several would send the user to a transcript they never asked about,
        // past the others the same notification is also standing for.
        assertNull(second.displayName)
    }

    @Test
    fun `a second finish drops the deleted-audio sentence`() {
        // It is a statement about one file. Carried over, it would read as being about all of them.
        val first = TranscriptNotice.after(null, "note.opus", audioDeleted = true)
        val second = TranscriptNotice.after(first, "call.ogg", audioDeleted = false)

        assertFalse(second.audioDeleted)
    }

    @Test
    fun `a batch of ten is one notification saying ten`() {
        // The whole point. One line in the shade, not ten — whoever queued them and however.
        var shade = TranscriptNotice.after(null, "0.ogg")
        repeat(9) { shade = TranscriptNotice.after(shade, "$it.ogg") }

        assertEquals(10, shade.count)
        assertNull(shade.displayName)
    }

    @Test
    fun `a dismissed notification starts the count again`() {
        // Nothing is stored anywhere but the shade, so a swipe is the reset — and it is the right
        // one: a user who cleared the last batch has seen it, and the next transcript is fresh news.
        val shade = TranscriptNotice.after(previous = null, displayName = "later.ogg")

        assertEquals(1, shade.count)
        assertEquals("later.ogg", shade.displayName)
    }
}
