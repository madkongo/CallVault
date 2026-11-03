/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Type-to-find in the transcription-language list: what a query finds, and that an empty box changes
 * nothing.
 */
class LanguageSearchTest {

    private val auto = TranscriptionLabels.AUTO_DETECT_KEY

    /** The list as an English-language app shows it: auto-detect first, then A-Z. */
    private val english = LanguageSearch.itemsFor(
        listOf(
            auto to "Detect automatically",
            "ar" to "Arabic",
            "zh" to "Chinese",
            "en" to "English",
            "fr" to "French",
            "de" to "German",
            "el" to "Greek",
            "he" to "Hebrew",
            "fa" to "Persian",
            "es" to "Spanish",
        )
    )

    /** The same languages as a Hebrew-language app labels them. */
    private val hebrewUi = LanguageSearch.itemsFor(
        listOf(
            auto to "זיהוי אוטומטי",
            "en" to "אנגלית",
            "he" to "עברית",
            "fr" to "צרפתית",
        )
    )

    private fun keys(items: List<LanguageSearchItem>, query: String) = LanguageSearch.filter(items, query).map { it.key }

    @Test
    fun an_empty_box_shows_the_whole_list_in_its_order() {
        assertEquals(english.map { it.key }, keys(english, ""))
        assertEquals(english.map { it.key }, keys(english, "   "))
    }

    @Test
    fun finds_by_the_shown_name() {
        assertEquals(listOf("he"), keys(english, "hebr"))
        assertEquals(listOf("fr"), keys(hebrewUi, "צרפ"))
    }

    @Test
    fun finds_by_english_name_when_the_app_is_in_another_language() {
        assertEquals(listOf("fr"), keys(hebrewUi, "french"))
    }

    @Test
    fun finds_by_native_name() {
        assertEquals(listOf("he"), keys(english, "עברית"))
        assertEquals(listOf("fa"), keys(english, "فارسی"))
        assertEquals(listOf("el"), keys(english, "Ελλην"))
        assertEquals(listOf("zh"), keys(english, "中文"))
    }

    @Test
    fun finds_by_code() {
        assertEquals(listOf("zh"), keys(english, "ZH"))
        assertTrue("he" in keys(english, "he"))
    }

    @Test
    fun ignores_case_and_accents() {
        assertEquals(listOf("es"), keys(english, "ESPANOL"))
        assertEquals(listOf("fr"), keys(english, "francais"))
        assertEquals(listOf("fr"), keys(english, "FRANÇAIS"))
        assertEquals(listOf("he"), keys(english, "עִבְרִית"))
    }

    @Test
    fun detect_automatically_is_found_by_its_label_only() {
        assertEquals(listOf(auto), keys(english, "detect"))
        assertEquals(listOf(auto), keys(hebrewUi, "אוטומטי"))
        // Its key ("auto") is not a name anyone types for it in another language.
        assertEquals(emptyList<String>(), keys(hebrewUi, "auto"))
    }

    @Test
    fun matches_keep_the_list_order() {
        // "an" is in Persian, German, Spanish… — they come back in the order the list shows them.
        val found = keys(english, "an")
        assertEquals(english.map { it.key }.filter { it in found }, found)
    }

    @Test
    fun no_match_gives_an_empty_list() {
        assertEquals(emptyList<String>(), keys(english, "klingon"))
    }
}
