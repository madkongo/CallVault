/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a baked "contact name" is really a phone number.
 *
 * It exists so a contact-only file-name template can still resolve names at display time: when no
 * contact was saved at record time, the formatter writes the NUMBER into the contact slot, and the
 * list has no parsed number to look up from. Reading that slot as a number — but only when it clearly
 * is one — lets a contact saved later show its name without re-recording (a tester's report (b)).
 *
 * The cost of a false positive is sending a real name to PhoneLookup, so the test leans on names
 * never being mistaken for numbers.
 */
class LooksLikePhoneNumberTest {

    private fun looksLikeNumber(value: String?) = RecordingsRepository.looksLikePhoneNumber(value)

    @Test
    fun a_plain_number_is_a_number() {
        assertTrue(looksLikeNumber("15551234567"))
    }

    @Test
    fun an_international_number_with_punctuation_is_a_number() {
        // The shapes getBestNumber() can produce and that survive into a filename.
        assertTrue(looksLikeNumber("+1 555 123 4567"))
        assertTrue(looksLikeNumber("+972-50-123-4567"))
        assertTrue(looksLikeNumber("(555) 123-4567"))
    }

    @Test
    fun a_person_name_is_never_a_number() {
        assertFalse(looksLikeNumber("John Doe"))
        assertFalse(looksLikeNumber("Mum"))
        // A name that happens to contain digits is still a name, not a lookup key.
        assertFalse(looksLikeNumber("Studio 54"))
        assertFalse(looksLikeNumber("אבא"))
    }

    @Test
    fun too_few_digits_is_not_a_number() {
        // A short code like a 3-digit extension would match too many contacts to be useful; require a
        // real number's worth of digits before touching PhoneLookup.
        assertFalse(looksLikeNumber("119"))
        assertFalse(looksLikeNumber("1-2"))
    }

    @Test
    fun blank_or_null_is_not_a_number() {
        assertFalse(looksLikeNumber(null))
        assertFalse(looksLikeNumber(""))
        assertFalse(looksLikeNumber("   "))
    }

    @Test
    fun surrounding_whitespace_is_ignored() {
        assertTrue(looksLikeNumber("  15551234567  "))
    }
}
