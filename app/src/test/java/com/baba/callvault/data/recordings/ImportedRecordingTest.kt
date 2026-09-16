/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name that keeps an imported file out of the sweeps.
 *
 * A file the user imported sits in the same folder as their calls, and everything that walks that
 * folder — the Drive upload, the retention delete, the storage cap — decides what to do from the
 * name alone. So the two failures pinned here are the ones that cost somebody their audio: a name
 * we wrote and then failed to recognise, and a recording that is recognised as an import because
 * the word happens to appear in a contact's name or an app's.
 */
class ImportedRecordingTest {

    /** 2026-09-16 10:10:10 UTC — only the shape of the stamp matters, never the zone. */
    private val importedAt = 1_789_567_810_000L

    // ---- the name we write

    @Test
    fun `a name we wrote is recognised again`() {
        val name = ImportedRecording.nameFor(importedAt, label = null, extension = ".m4a")
        assertTrue(name, ImportedRecording.isImported(name))
        assertTrue(name, name.endsWith("_import.m4a"))
    }

    @Test
    fun `a labelled name is recognised, and keeps its label`() {
        val name = ImportedRecording.nameFor(importedAt, label = "Standup", extension = ".m4a")
        assertTrue(name, ImportedRecording.isImported(name))
        assertTrue(name, name.endsWith("_import_Standup.m4a"))
    }

    @Test
    fun `the stamp opens the name, so imports sort with the recordings around them`() {
        val name = ImportedRecording.nameFor(importedAt, label = null, extension = "ogg")
        assertTrue(name, Regex("""^\d{8}_\d{6}\.\d{3}[+-]\d{4}_import\.ogg$""").matches(name))
    }

    @Test
    fun `an extension with no dot still yields one`() {
        val name = ImportedRecording.nameFor(importedAt, label = null, extension = "opus")
        assertTrue(name, name.endsWith("_import.opus"))
    }

    // ---- the label

    @Test
    fun `the source extension never rides into the label`() {
        // Otherwise the name ends "_voice note.m4a.ogg", and the import is named after its source
        // verbatim — which is exactly what delete-by-name must never be able to confuse.
        assertEquals("voice note", ImportedRecording.labelFor("voice note.m4a"))
        val name = ImportedRecording.nameFor(importedAt, label = "voice note.m4a", extension = ".ogg")
        assertFalse(name, name.contains("voice note.m4a"))
    }

    @Test
    fun `an underscore in the label cannot open a slot of its own`() {
        assertEquals("teamsync", ImportedRecording.labelFor("team_sync"))
        val name = ImportedRecording.nameFor(importedAt, label = "team_sync", extension = ".ogg")
        assertTrue(name, ImportedRecording.isImported(name))
    }

    @Test
    fun `path separators and reserved characters are dropped`() {
        assertEquals("abc", ImportedRecording.labelFor("""a/b\c"""))
        assertEquals("note", ImportedRecording.labelFor("""no*te?"""))
    }

    @Test
    fun `a label that is only decoration drops out rather than being guessed at`() {
        assertNull(ImportedRecording.labelFor(null))
        assertNull(ImportedRecording.labelFor("   "))
        assertNull(ImportedRecording.labelFor("___"))
        assertNull(ImportedRecording.labelFor(".m4a"))
    }

    @Test
    fun `a leading dot is stripped, so an import is never a hidden file`() {
        assertEquals("hidden", ImportedRecording.labelFor(".hidden.m4a"))
    }

    @Test
    fun `a very long label is capped`() {
        val label = ImportedRecording.labelFor("x".repeat(200))
        assertEquals(40, label?.length)
    }

    // ---- what is NOT an import

    @Test
    fun `a call with a contact called Important is not an import`() {
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_in_Important.ogg"))
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_out_Import.ogg"))
    }

    @Test
    fun `a VoIP app or caller carrying the word is not an import`() {
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_voip-Import.ogg"))
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_voip-WhatsApp_import.ogg"))
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_voip_import.ogg"))
    }

    @Test
    fun `an ordinary call is not an import`() {
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_in_5551234.ogg"))
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_voip-Signal.ogg"))
    }

    @Test
    fun `a stranger's file with the word in it is not an import`() {
        // The recordings folder is whichever folder the user picked, and may hold their own audio.
        assertFalse(ImportedRecording.isImported("import.mp3"))
        assertFalse(ImportedRecording.isImported("my_music_import.mp3"))
        assertFalse(ImportedRecording.isImported("2026_0916_import.mp3"))
        assertFalse(ImportedRecording.isImported("important.mp3"))
    }

    // ---- the two kinds

    @Test
    fun `a transcribe-only name says so, and is still an import`() {
        val name = ImportedRecording.nameFor(
            importedAt,
            label = null,
            extension = ".ogg",
            kind = ImportedRecording.Kind.TRANSCRIBE_ONLY,
        )
        // Still an import first and foremost: every sweep gate keys on this, so a transcribe-only
        // file that stopped reading as an import would be uploaded to Drive and aged out.
        assertTrue(name, ImportedRecording.isImported(name))
        assertTrue(name, ImportedRecording.isTranscribeOnly(name))
        assertEquals(ImportedRecording.Kind.TRANSCRIBE_ONLY, ImportedRecording.kindOf(name))
        assertTrue(name, name.endsWith("_import_transcribeonly.ogg"))
    }

    @Test
    fun `a kept import is not transcribe-only`() {
        val name = ImportedRecording.nameFor(importedAt, label = "Standup", extension = ".m4a")
        assertEquals(ImportedRecording.Kind.KEEP, ImportedRecording.kindOf(name))
        assertFalse(name, ImportedRecording.isTranscribeOnly(name))
    }

    @Test
    fun `a transcribe-only name keeps its label, and the label is not the kind token`() {
        val name = ImportedRecording.nameFor(
            importedAt,
            label = "voice note.opus",
            extension = ".opus",
            kind = ImportedRecording.Kind.TRANSCRIBE_ONLY,
        )
        assertTrue(name, name.endsWith("_import_transcribeonly_voice note.opus"))
        assertEquals("voice note", ImportedRecording.labelOf(name))
        assertTrue(name, ImportedRecording.isTranscribeOnly(name))
    }

    @Test
    fun `a file actually called transcribeonly cannot turn a kept import into a deletable one`() {
        // The one way a user could otherwise reach the kind slot. Losing the label costs nothing;
        // misreading it would delete audio they asked to keep.
        assertNull(ImportedRecording.labelFor("transcribeonly.m4a"))
        assertNull(ImportedRecording.labelFor("TranscribeOnly"))
        val name = ImportedRecording.nameFor(importedAt, label = "transcribeonly.m4a", extension = ".m4a")
        assertFalse(name, ImportedRecording.isTranscribeOnly(name))
        assertEquals(ImportedRecording.Kind.KEEP, ImportedRecording.kindOf(name))
    }

    @Test
    fun `the kind token sits after the marker, so an older build still sees an import`() {
        // The downgrade guarantee, pinned: a build that has never heard of the kind reads the marker
        // slot alone. Written as "_import-transcribeonly" it would have read "not an import", and
        // handed somebody's voice note to the Drive upload and the retention sweep.
        val name = ImportedRecording.nameFor(
            importedAt,
            label = null,
            extension = ".ogg",
            kind = ImportedRecording.Kind.TRANSCRIBE_ONLY,
        )
        val markerSlot = name.substringBeforeLast('.').split('_')[2]
        assertEquals(ImportedRecording.TOKEN, markerSlot)
    }

    @Test
    fun `a call or stranger file is never transcribe-only`() {
        assertFalse(ImportedRecording.isTranscribeOnly("20260916_101010.123+0300_in_5551234.ogg"))
        assertFalse(ImportedRecording.isTranscribeOnly("20260916_101010.123+0300_voip-WhatsApp.ogg"))
        assertFalse(ImportedRecording.isTranscribeOnly("transcribeonly.mp3"))
        assertNull(ImportedRecording.kindOf("20260916_101010.123+0300_in_5551234.ogg"))
    }

    @Test
    fun `a name stored without an extension is still read correctly`() {
        // The stamp carries dots of its own, so "chop at the last dot" would eat half the name.
        assertTrue(ImportedRecording.isImported("20260916_101010.123+0300_import"))
        assertFalse(ImportedRecording.isImported("20260916_101010.123+0300_in_5551234"))
    }
}
