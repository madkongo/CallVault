/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Which alphabet each offered transcription language is written in.
 *
 * One table, because two rules read it and must agree: [TranscriptionPrompt] will not prime whisper with
 * a name in another script, and [WrongScriptRetry] treats a transcript in another script as a failed
 * decode. Only the languages that are NOT Latin are listed; every other entry in
 * [TranscriptionLanguageChoice.SUPPORTED] is Latin today.
 */
object LanguageScript {

    private val NON_LATIN = mapOf(
        "he" to Character.UnicodeScript.HEBREW,
        "ar" to Character.UnicodeScript.ARABIC,
        "ru" to Character.UnicodeScript.CYRILLIC,
        "zh" to Character.UnicodeScript.HAN,
    )

    fun of(language: String): Character.UnicodeScript = NON_LATIN[language] ?: Character.UnicodeScript.LATIN

    /** Whether [language] is written in something other than Latin letters — the only case a script can catch. */
    fun isNonLatin(language: String): Boolean = language in NON_LATIN
}
