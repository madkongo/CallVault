/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sharing several rows at once: what the one message says, and when it has to become a file. */
class BulkTextShareTest {

    @Test
    fun every_selected_row_is_named_above_its_own_words() {
        // Without the names a wall of sentences from several conversations is unreadable — and,
        // worse, quotable with the wrong person attached to it.
        val joined = BulkTextShare.join(
            listOf(
                BulkTextShare.Item(title = "Dad", text = "call me back"),
                BulkTextShare.Item(title = "The bank", text = "your appointment is Tuesday"),
            )
        )
        assertTrue(joined.contains("Dad"))
        assertTrue(joined.contains("call me back"))
        assertTrue(joined.contains("The bank"))
        assertTrue(joined.contains("your appointment is Tuesday"))
        assertTrue(joined.indexOf("Dad") < joined.indexOf("The bank"))
    }

    @Test
    fun a_row_with_nothing_in_it_is_dropped_rather_than_titled_over_a_blank() {
        // A DONE transcript of a call in which nobody spoke is a real row. A heading with nothing
        // under it reads as text that went missing on the way.
        val joined = BulkTextShare.join(
            listOf(
                BulkTextShare.Item(title = "Silence", text = "   "),
                BulkTextShare.Item(title = "Dad", text = "call me back"),
            )
        )
        assertFalse(joined.contains("Silence"))
        assertTrue(joined.startsWith("Dad"))
    }

    @Test
    fun an_empty_selection_shares_nothing() {
        assertEquals("", BulkTextShare.join(emptyList()))
    }

    @Test
    fun an_ordinary_selection_goes_inline() {
        assertFalse(BulkTextShare.mustBeAFile("a".repeat(BulkTextShare.MAX_INLINE_CHARS)))
    }

    @Test
    fun a_selection_too_large_for_an_intent_goes_as_a_file() {
        // 🚨 The crash guard. Intent extras travel through the binder, whose transaction buffer is
        // shared by the whole process, and going over it raises TransactionTooLargeException out of
        // startActivity — an unhandled crash at the moment the user taps Share.
        assertTrue(BulkTextShare.mustBeAFile("a".repeat(BulkTextShare.MAX_INLINE_CHARS + 1)))
    }
}
