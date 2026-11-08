/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import java.util.Locale

/**
 * The names a transcription language can be searched by, beyond the translated label the list shows.
 *
 * Someone looking for their own language types it the way they write it — עברית, اردو, Ελληνικά —
 * whatever language the app happens to be in, and a phone borrowed or set up in English is exactly
 * when that matters. So each language carries its **endonym** (its name in itself), and its English
 * name, which is what most people reach for when the app is in a language they do not read.
 *
 * The endonyms are a hand-written table rather than `Locale.getDisplayLanguage(itself)`, because the
 * platform's answer differs between Android releases and is lower-case for several languages; a
 * table says exactly what is matched and can be tested. A language missing from it is not an error at
 * run time — it simply cannot be found by its native name — and `LanguageNamesTest` fails instead, so
 * the gap is caught at build time rather than by a user.
 */
object LanguageNames {

    /**
     * Each language's name in itself, keyed by the code in [TranscriptionLanguageChoice.SUPPORTED].
     *
     * "ur" is here ahead of Urdu itself reaching SUPPORTED (a parallel branch adds it): an extra entry
     * is inert, and having it already means that merge needs no change here.
     */
    private val ENDONYMS: Map<String, String> = mapOf(
        "ar" to "العربية",
        "bn" to "বাংলা",
        "de" to "Deutsch",
        "el" to "Ελληνικά",
        "en" to "English",
        "es" to "Español",
        "fa" to "فارسی",
        "fr" to "Français",
        "he" to "עברית",
        "hu" to "Magyar",
        "it" to "Italiano",
        "pl" to "Polski",
        "pt" to "Português",
        "ru" to "Русский",
        "uk" to "Українська",
        "ur" to "اردو",
        "vi" to "Tiếng Việt",
        "zh" to "中文",
    )

    /** [code]'s name in itself, or null when the table has none (a test guards SUPPORTED against that). */
    fun endonymOf(code: String): String? = ENDONYMS[code]

    /** [code]'s English name ("he" → "Hebrew"), or null when the platform does not know the code. */
    fun englishNameOf(code: String): String? =
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
}
