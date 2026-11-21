/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C138: a dump the recorder sends over the binder must fit under its 1 MB limit, or the report gets nothing. */
class DiagnosticDumpCapTest {

    private fun read(text: String, max: Int) =
        DiagnosticDumps.readCapped(ByteArrayInputStream(text.toByteArray()), max)

    @Test
    fun `a short dump passes through untouched`() {
        assertEquals("a\nb\n", read("a\nb\n", 100))
    }

    @Test
    fun `a dump past the cap keeps its start, says how much was left out, and stays bounded`() {
        val text = "x".repeat(1_000)
        val out = read(text, 100)
        assertTrue(out.startsWith("x".repeat(100)))
        assertTrue(out, out.contains("900"))
        assertTrue(out.length < 300)
    }

    @Test
    fun `a dump exactly at the cap is not marked as cut`() {
        val out = read("y".repeat(100), 100)
        assertEquals("y".repeat(100), out)
        assertFalse(out.contains("cut"))
    }

    @Test
    fun `the generic dumps are capped under the binder limit with room to spare`() {
        // UTF-16 on the wire: twice the characters.
        assertTrue(DiagnosticDumps.DUMP_MAX_CHARS * 2 < 600_000)
    }
}
