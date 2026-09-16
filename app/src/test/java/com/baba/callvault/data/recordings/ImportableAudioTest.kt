/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the picker will and will not take, decided at a desk.
 *
 * The device half of this — whether the format really decodes — is
 * `com.baba.callvault.transcription.AudioImportFormatProbe`. These are the cases that need no
 * device: what an extension resolves to, what happens when there is none, and the spellings a
 * provider may use for the same type.
 */
class ImportableAudioTest {

    @Test
    fun `a whatsapp voice note is accepted and stored as opus`() {
        val stored = ImportableAudio.storedAs("PTT-20260916-WA0003.opus", "audio/ogg")
        assertEquals("opus", stored?.extension)
        assertEquals("audio/ogg", stored?.mimeType)
    }

    @Test
    fun `the extension wins over a useless provider type`() {
        // The common real case: a file manager hands back octet-stream for a file whose name says
        // exactly what it is. Believing the type would refuse every import from such a provider.
        val stored = ImportableAudio.storedAs("meeting.m4a", "application/octet-stream")
        assertEquals("m4a", stored?.extension)
        assertEquals("audio/mp4", stored?.mimeType)
    }

    @Test
    fun `a source with no name falls back to its type`() {
        val stored = ImportableAudio.storedAs(null, "audio/mpeg")
        assertEquals("mp3", stored?.extension)
        assertEquals("audio/mpeg", stored?.mimeType)
    }

    @Test
    fun `an alternate spelling of the same type resolves to our own spelling`() {
        // Stored under the type we write, not the one the provider reported: the copy's name and
        // type have to be what the folder scan looks for on the next catalog re-seed.
        val stored = ImportableAudio.storedAs(null, "audio/x-wav")
        assertEquals("wav", stored?.extension)
        assertEquals("audio/wav", stored?.mimeType)
    }

    @Test
    fun `a type carrying parameters still matches`() {
        val stored = ImportableAudio.storedAs(null, "audio/mp4; codecs=mp4a.40.2")
        assertEquals("m4a", stored?.extension)
    }

    @Test
    fun `the extension is read case-insensitively`() {
        assertEquals("mp3", ImportableAudio.storedAs("VOICE.MP3", null)?.extension)
    }

    @Test
    fun `a video file is refused`() {
        assertNull(ImportableAudio.storedAs("clip.mkv", "video/x-matroska"))
    }

    @Test
    fun `a document is refused`() {
        assertNull(ImportableAudio.storedAs("notes.pdf", "application/pdf"))
    }

    @Test
    fun `a nameless source of an unknown type is refused rather than guessed at`() {
        // Storing audio under an extension nothing can open is the failure this object exists to
        // prevent, so an unanswerable source is turned down instead of given a default.
        assertNull(ImportableAudio.storedAs(null, null))
        assertNull(ImportableAudio.storedAs("", "application/octet-stream"))
    }

    @Test
    fun `a dotfile has no extension`() {
        // ".opus" is a hidden file called opus, not an opus file, and taking "opus" from it would
        // import something on the strength of a name that says nothing.
        assertNull(ImportableAudio.extensionOf(".opus"))
        assertNull(ImportableAudio.storedAs(".opus", null))
    }

    @Test
    fun `a trailing dot has no extension`() {
        assertNull(ImportableAudio.extensionOf("recording."))
    }

    @Test
    fun `every accepted extension resolves to itself`() {
        // Guards the one mistake the map's shape allows: an extension listed as accepted that
        // storedAs cannot actually resolve, which would make the folder scan recognise a file the
        // importer would never have written.
        for (extension in ImportableAudio.ACCEPTED_EXTENSIONS) {
            assertEquals(extension, ImportableAudio.storedAs("voice.$extension", null)?.extension)
        }
    }
}
