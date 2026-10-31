/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.transcription.LanguageNames
import java.text.Normalizer
import java.util.Locale

/**
 * One row of the language picker: what it selects, what it shows, and every name that finds it.
 *
 * @property key the encoded language, or [TranscriptionLabels.AUTO_DETECT_KEY].
 * @property label the name shown, in the app's language.
 * @property searchTerms the names typing can match, already normalised by [LanguageSearch.normalize].
 */
data class LanguageSearchItem(
    val key: String,
    val label: String,
    val searchTerms: List<String>,
)

/**
 * Type-to-find for the transcription-language list. Pure, so it is tested off the device.
 *
 * A language matches when the query is contained in its shown name, its English name, its name in
 * itself, or its code — ignoring case and accents, so "espanol" finds Español and "francais" finds
 * Français. "Detect automatically" is not a language and is found by its shown name only.
 *
 * An empty query returns the list untouched: same entries, same order, auto-detect first. The search
 * is a way to *narrow* the familiar list, never a different one.
 */
object LanguageSearch {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * [options] — (key, shown name) pairs already in display order, as
     * [TranscriptionLabels.sortLanguageOptions] returns them — with their search terms attached.
     */
    fun itemsFor(options: List<Pair<String, String>>): List<LanguageSearchItem> =
        options.map { (key, label) ->
            val names = if (key == TranscriptionLabels.AUTO_DETECT_KEY) {
                listOf(label)
            } else {
                listOfNotNull(label, LanguageNames.englishNameOf(key), LanguageNames.endonymOf(key), key)
            }
            LanguageSearchItem(key, label, names.map(::normalize).filter { it.isNotEmpty() }.distinct())
        }

    /** The [items] whose names contain [query]; all of them, in order, when [query] is blank. */
    fun filter(items: List<LanguageSearchItem>, query: String): List<LanguageSearchItem> {
        val needle = normalize(query)
        if (needle.isEmpty()) return items
        return items.filter { item -> item.searchTerms.any { it.contains(needle) } }
    }

    /**
     * [text] folded for comparison: decomposed, stripped of combining marks (accents, and Hebrew and
     * Arabic vowel points), lower-cased without regard to the phone's locale, and trimmed.
     *
     * Locale.ROOT for the case, because a Turkish phone lower-cases "I" to a dotless ı and would then
     * fail to find "Italian".
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .trim()
}
