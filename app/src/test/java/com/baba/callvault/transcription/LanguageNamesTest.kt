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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names a language can be searched by.
 *
 * The guard here is the first test: a language added to SUPPORTED without its native name would still
 * work, but could not be found the way its own speakers write it. Failing here rather than in the app
 * is the point of keeping the endonyms in a table.
 */
class LanguageNamesTest {

    @Test
    fun every_supported_language_has_a_native_name() {
        val missing = TranscriptionLanguageChoice.SUPPORTED.filter { LanguageNames.endonymOf(it).isNullOrBlank() }

        assertTrue("No endonym in LanguageNames for: $missing", missing.isEmpty())
    }

    @Test
    fun every_supported_language_has_an_english_name() {
        val missing = TranscriptionLanguageChoice.SUPPORTED.filter { LanguageNames.englishNameOf(it).isNullOrBlank() }

        assertTrue("No English name for: $missing", missing.isEmpty())
    }

    @Test
    fun native_names_are_written_in_the_language_itself() {
        assertEquals("עברית", LanguageNames.endonymOf("he"))
        assertEquals("فارسی", LanguageNames.endonymOf("fa"))
        assertEquals("اردو", LanguageNames.endonymOf("ur"))
        assertEquals("বাংলা", LanguageNames.endonymOf("bn"))
    }

    @Test
    fun english_names_come_from_the_code() {
        assertEquals("Hebrew", LanguageNames.englishNameOf("he"))
        assertEquals("Persian", LanguageNames.englishNameOf("fa"))
    }

    @Test
    fun an_unknown_code_has_no_names_rather_than_failing() {
        assertNull(LanguageNames.endonymOf("xx"))
        assertFalse(LanguageNames.englishNameOf("xx") == "xx")
    }
}
