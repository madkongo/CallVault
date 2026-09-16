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
 * How an imported file reads once it is back out of the folder as a name.
 *
 * The parser is what every row, filter and badge in the app sees, so this is where "an import is not
 * a call" either holds or quietly stops holding. What it must produce: no direction, no number, the
 * date it was imported, and the label where a contact name would go.
 */
class ImportedFileNameParsingTest {

    private fun parse(name: String) = RecordingsRepository.parseName(name)

    @Test
    fun `an import has no direction and no number`() {
        val parsed = parse("20260916_101010.123+0300_import_voice-note.ogg")
        assertNull(parsed.direction)
        assertNull(parsed.number)
        assertTrue(parsed.isImported)
    }

    @Test
    fun `the label reads where a contact name would`() {
        val parsed = parse("20260916_101010.123+0300_import_voice-note.ogg")
        assertEquals("voice-note", parsed.contactName)
    }

    @Test
    fun `an import with no label still parses`() {
        val parsed = parse("20260916_101010.123+0300_import.m4a")
        assertTrue(parsed.isImported)
        assertNull(parsed.contactName)
        assertEquals("2026-09-16 10:10", parsed.displayDate)
    }

    @Test
    fun `the date is the import time and is readable`() {
        val parsed = parse("20260916_101010.123+0300_import_note.opus")
        assertEquals("2026-09-16 10:10", parsed.displayDate)
        // Not null: an undated row is exactly what the row's date fallback exists to paper over, and
        // an import should never need it — the name carries a real stamp.
        assertTrue(parsed.startedAtMillis != null && parsed.startedAtMillis!! > 0L)
    }

    @Test
    fun `a call with a contact called Important is still a call`() {
        // The whole reason recognition reads the marker slot rather than searching the name. An
        // exemption handed out by accident is a recording that quietly stops being backed up, never
        // expires, and loses its direction in the list.
        val parsed = parse("20260916_101010.123+0300_in_Important.ogg")
        assertFalse(parsed.isImported)
        assertEquals(RecordingDirection.INCOMING, parsed.direction)
        assertEquals("Important", parsed.number)
    }

    @Test
    fun `a voip call from an app called Import is still a voip call`() {
        val parsed = parse("20260916_101010.123+0300_voip-Import_Dana.ogg")
        assertFalse(parsed.isImported)
        assertEquals("Import", parsed.voipApp)
        assertEquals("Dana", parsed.contactName)
    }

    @Test
    fun `an ordinary call is untouched by the new branch`() {
        val parsed = parse("20260916_101010.123+0300_out_0501234567.ogg")
        assertFalse(parsed.isImported)
        assertEquals(RecordingDirection.OUTGOING, parsed.direction)
        assertEquals("0501234567", parsed.number)
    }

    @Test
    fun `a label carrying dots keeps them`() {
        // ImportedRecording strips the SOURCE extension when it builds the label, but a name like
        // "v2.1 draft" legitimately has a dot in it, and the stored name's own extension is what the
        // parser must chop — not the first dot it meets.
        val parsed = parse("20260916_101010.123+0300_import_v2.1 draft.mp3")
        assertTrue(parsed.isImported)
        assertEquals("v2.1 draft", parsed.contactName)
    }

    @Test
    fun `an import stored without an extension still parses`() {
        // Not a shape the importer writes, but the parser must not mangle it: chopping at the last
        // dot unconditionally would cut the timestamp in half and lose the date.
        val parsed = parse("20260916_101010.123+0300_import_note")
        assertTrue(parsed.isImported)
        assertEquals("note", parsed.contactName)
        assertEquals("2026-09-16 10:10", parsed.displayDate)
    }

    @Test
    fun `the stamp and the label come back out of the name they went in as`() {
        val name = ImportedRecording.nameFor(
            // 2026-09-16 10:10:10.123 UTC, so the assertion does not depend on the test machine.
            importedAtMillis = 1789553410123L,
            label = "PTT-20260916-WA0003.opus",
            extension = "ogg",
        )
        assertTrue(ImportedRecording.isImported(name))
        assertEquals("PTT-20260916-WA0003", ImportedRecording.labelOf(name))
        assertEquals("PTT-20260916-WA0003", parse(name).contactName)
        assertEquals(1789553410123L, parse(name).startedAtMillis)
    }
}
