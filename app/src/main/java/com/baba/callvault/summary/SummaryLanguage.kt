/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

/**
 * Deciding, before the prompt is built, which language a summary will be written in.
 *
 * **The prompt must always name a concrete language.** The instruction it used to fall back to —
 * "write the summary in the same language as the conversation" — asks the model to make a judgement
 * it cannot reliably make. Handed a garbled or language-mixed transcript there is no identifiable
 * main language, and a small model resolves that by writing English.
 *
 * That is measured rather than suspected. A sibling project running the same shape of prompt over
 * real Hebrew calls found **35 English summaries out of 196, 23 of them from Hebrew transcripts**,
 * and fixed it by pinning the language instead of asking the model to infer it. This is that fix,
 * with the pin chosen per recording rather than set once for everyone.
 */
object SummaryLanguage {

    /**
     * The language code to write in, resolved from the best evidence available.
     *
     * In order, and the order is the point — each step is more certain than the one after it:
     *
     * 1. [chosen] — the user said so in Settings. Nothing overrides being told.
     * 2. [transcriptLanguage] — what this transcript was actually made in.
     * 3. [transcriptionSetting] — the user pinned a language for whisper. Saying so once is enough.
     * 4. [transcriptText] — the script the words are written in, which for Hebrew, Arabic, Cyrillic
     *    and CJK is decisive. This is the step that rescues auto-detect, where 2 and 3 are both null.
     * 5. [deviceLanguage] — a genuine unknown. The phone's own language is a far better guess than
     *    defaulting to English, which is the failure being fixed.
     *
     * Never returns null. An absent answer would put the old instruction back in the prompt.
     */
    fun resolve(
        chosen: String?,
        transcriptionSetting: String?,
        transcriptLanguage: String?,
        transcriptText: String,
        deviceLanguage: String
    ): String =
        chosen
            ?: transcriptLanguage
            ?: transcriptionSetting
            ?: scriptOf(transcriptText)
            ?: normalised(deviceLanguage).ifBlank { FINAL_FALLBACK }

    /** Java still reports a Hebrew phone as "iw" (and Indonesian as "in"); the prompt needs the modern code. */
    private fun normalised(code: String): String = LEGACY_LANGUAGE_CODES[code] ?: code

    private val LEGACY_LANGUAGE_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * The language a text's script implies, or null when the script does not imply one.
     *
     * **Null is a real answer.** Latin script is shared by English, Spanish, French, German and most
     * of the rest, so returning "en" for it would be a guess dressed as a detection — and guessing
     * English is precisely the bug. Only scripts used by essentially one of the languages on offer
     * are reported.
     *
     * Decided by weight of letters rather than by presence. A real Hebrew transcript carries brand
     * names, numbers and the odd English word; a single "Zoom" must not outvote the call.
     */
    fun scriptOf(text: String): String? {
        val counts = mutableMapOf<String, Int>()

        text.forEach { ch ->
            val code = ch.code
            val language = when {
                code in 0x0590..0x05FF -> "he"
                code in 0x0600..0x06FF || code in 0x0750..0x077F -> "ar"
                code in 0x0400..0x04FF -> "ru"
                code in 0x0370..0x03FF || code in 0x1F00..0x1FFF -> "el"
                // CJK ideographs. Japanese kana are excluded on purpose: this app offers no
                // Japanese, so a kana-heavy text has no honest answer here.
                code in 0x4E00..0x9FFF -> "zh"
                // Bengali script. Assamese shares it, but this app offers no Assamese.
                code in 0x0980..0x09FF -> "bn"
                else -> null
            }
            if (language != null) counts[language] = (counts[language] ?: 0) + 1
        }

        val (language, count) = counts.maxByOrNull { it.value } ?: return null
        // Enough letters to be the text rather than a quotation inside it.
        if (count < MINIMUM_LETTERS) return null
        return when {
            language == "ar" -> arabicScriptLanguage(text)
            language == "ru" && writesUkrainian(text) -> "uk"
            else -> language
        }
    }

    /**
     * Whether Cyrillic [text] is Ukrainian rather than Russian. Like Persian and Arabic, they share the
     * script, so only the letters each one alone uses can tell them apart: Ukrainian writes і ї є ґ,
     * Russian writes ы э ъ ё. Weighed, not spotted.
     */
    private fun writesUkrainian(text: String): Boolean {
        val ukrainian = text.count { it in UKRAINIAN_ONLY }
        val russian = text.count { it in RUSSIAN_ONLY }
        return ukrainian > russian
    }

    /**
     * Which Arabic-script language [text] is: Arabic, Persian (#55) or Urdu. They share the script, so
     * only the letters each one alone uses can tell them apart, and every step is weighed rather than
     * spotted, like the scripts above — one loanword must not decide it.
     *
     * 1. Arabic or not. Persian and Urdu both add پ چ ژ گ and write their own ی and ک, where Arabic writes
     *    ي ك and ة. This is the original Persian-vs-Arabic rule, unchanged for any text with no Urdu letter.
     * 2. Persian or Urdu. Those shared letters cannot split them, so Urdu's own letters do: ٹ ڈ ڑ ں ے and
     *    its own heh forms ہ ھ ۃ. Persian writes plain ه where Urdu writes ہ, so ه is what Urdu letters are
     *    weighed against — a stray ے in a Persian sentence is outvoted by its ه.
     */
    private fun arabicScriptLanguage(text: String): String {
        val urdu = text.count { it in URDU_ONLY }
        val persianOrUrdu = text.count { it in PERSIAN_OR_URDU }
        val arabic = text.count { it in ARABIC_ONLY }
        return when {
            persianOrUrdu + urdu <= arabic -> "ar"
            urdu > text.count { it == PLAIN_HEH } -> "ur"
            else -> "fa"
        }
    }

    /** پ چ ژ گ ی ک: written by Persian and Urdu, not by Arabic. */
    private const val PERSIAN_OR_URDU = "\u067E\u0686\u0698\u06AF\u06CC\u06A9"

    /** ٹ ڈ ڑ ں ے ہ ھ ۃ: written by Urdu only, of the languages on offer. */
    private const val URDU_ONLY = "\u0679\u0688\u0691\u06BA\u06D2\u06C1\u06BE\u06C3"

    /** ه: Persian's and Arabic's heh, which Urdu writes as ہ or ھ. */
    private const val PLAIN_HEH = '\u0647'
    private const val ARABIC_ONLY = "\u064A\u0643\u0629"
    private const val UKRAINIAN_ONLY = "іїєґІЇЄҐ"
    private const val RUSSIAN_ONLY = "ыэъёЫЭЪЁ"

    /**
     * How many letters of a script it takes before it is taken as the language.
     *
     * Low, because the alternative to answering is guessing English. A handful of Hebrew characters
     * in a transcript is not a coincidence.
     */
    private const val MINIMUM_LETTERS = 3

    /** Used only when the device reports no language at all, which should not happen. */
    private const val FINAL_FALLBACK = "en"
}
