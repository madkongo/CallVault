/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.text.Normalizer
import java.util.Locale

/**
 * The search line in the Settings panel (option B, chosen by the maintainer 2026-11-03): every group with a
 * matching row opens and shows only those rows, the rest are dimmed.
 */
internal object SettingsSearch {

    private val MARKS = Regex("\\p{Mn}+")

    /** Lower case, accents dropped, so "Télé" finds "tele" and "WhatsApp" finds "whatsapp". */
    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /**
     * The rows whose texts contain every word of [query] (each word anywhere in the row's texts).
     *
     * @param texts per row, everything it can be found by (both languages and the everyday words), normalized.
     */
    fun matchingRows(texts: Map<String, String>, query: String): Set<String> {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptySet()
        return texts.filterValues { haystack -> words.all { haystack.contains(it) } }.keys
    }

    /** Whether a row shows under the search's [matches] (null: nobody is searching). See [SearchFiltered]. */
    fun isShown(matches: Set<String>?, name: String, unfolds: Set<String>): Boolean =
        matches == null || name in matches || unfolds.any { it in matches }

    /** Builds [matchingRows]' texts: each row's strings in the app's language and in English, plus its words. */
    fun textsFor(context: Context): Map<String, String> {
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )
        return SettingsSearchIndex.rows.mapValues { (_, entry) ->
            val parts = entry.strings.flatMap { id ->
                listOfNotNull(runCatching { context.getString(id) }.getOrNull(), runCatching { english.getString(id) }.getOrNull())
            } + entry.words
            normalize(parts.joinToString(" \u0000 "))
        }
    }
}

/** The rows a search shows, or null while nobody is searching (every row shows, as always). */
internal val LocalSettingsSearchRows = compositionLocalOf<Set<String>?> { null }

/** True while a search narrows the rows: dividers and sub-headers then step aside. */
@Composable
internal fun isSettingsSearchActive(): Boolean = LocalSettingsSearchRows.current != null

/**
 * Draws [content] (the row called [name]) unless a search is on and neither this row nor any of [unfolds] is
 * one of its matches.
 *
 * [unfolds] names the rows this one folds in (a switch and the rows shown only while it is on). A search that
 * matches a folded row while the switch is off would otherwise open its group with nothing in it, so the
 * switch shows instead, and turning it on brings the matched row in.
 */
@Composable
internal fun SearchFiltered(name: String, unfolds: Set<String> = emptySet(), content: @Composable () -> Unit) {
    if (SettingsSearch.isShown(LocalSettingsSearchRows.current, name, unfolds)) content()
}

/** The texts for the current language, rebuilt only when the language changes. */
@Composable
internal fun rememberSettingsSearchTexts(): Map<String, String> {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { SettingsSearch.textsFor(context) }
}

/** Provides the rows a search shows to everything below it. */
@Composable
internal fun WithSettingsSearchMatches(rows: Set<String>?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsSearchRows provides rows, content = content)
}
