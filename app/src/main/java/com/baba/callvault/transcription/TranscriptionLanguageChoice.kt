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
 * Which language one transcription runs in: the language picked for that recording if there was one,
 * and the Settings language otherwise.
 *
 * **Why a choice exists at all.** The language is pinned rather than detected because auto-detect is
 * measurably worse — it writes Hebrew in Latin letters and merges a whole call into one segment. The pin
 * is real from the first launch: [defaultLanguage] seeds it from the phone's own language, and the setup
 * wizard asks outright, so nobody transcribes on auto-detect without having chosen it. But a pin is one
 * answer for every call, and a phone takes calls in more than one language: an English call transcribed
 * under a Hebrew pin comes back unusable. So a per-recording answer is offered, off by default
 * (`AppPreferences.getTranscriptionAskLanguage`), and it is one-shot — picking English for one call does
 * not quietly repin the phone to English.
 *
 * **Why the choice is encoded rather than passed as a nullable string.** It travels through WorkManager
 * input data, where "the user chose auto-detect" and "the user chose nothing" would both arrive as null
 * and the setting would silently win over an explicit choice. [AUTO] is that missing value. Stored
 * settings have the same problem and the same answer — see `AppPreferences.getTranscriptionLanguage`.
 */
object TranscriptionLanguageChoice {

    /** Stands in for auto-detect, which has no language code of its own. Never sent to whisper. */
    const val AUTO = "auto"

    /**
     * The language codes the app offers, in no particular order — the picker sorts them by translated
     * name. Whisper knows far more; these are the ones with a translated label to show for them.
     */
    val SUPPORTED: List<String> =
        listOf("he", "en", "ar", "zh", "fr", "de", "hu", "it", "pl", "pt", "ru", "es", "vi", "el", "fa", "uk", "ur", "bn")

    /**
     * The obsolete ISO 639 codes [Locale] still reports, mapped to the modern ones whisper uses.
     *
     * Java froze `he` as `iw` (and `id` as `in`, `yi` as `ji`) in 1989 and never unfroze it, so a Hebrew
     * phone reports "iw" — which is not in [SUPPORTED] and would have sent exactly the users this
     * defaulting is for straight back to auto-detect.
     */
    private val LEGACY_LANGUAGE_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * The language to pin when the user has not said: the phone's own, or null (auto-detect) when the
     * app does not offer it.
     *
     * A guess, but the best one available without asking, and far better than the alternative — a phone
     * set to Hebrew takes Hebrew calls, and auto-detect on those returns Latin-letter transliteration.
     * Callers that can ask instead should ask; the setup wizard does.
     */
    fun defaultLanguage(locale: Locale = Locale.getDefault()): String? {
        val code = locale.language.lowercase(Locale.ROOT)
        return (LEGACY_LANGUAGE_CODES[code] ?: code).takeIf { it in SUPPORTED }
    }

    /** [language] (null meaning auto-detect) as a value that survives a trip through work input data. */
    fun encode(language: String?): String = language ?: AUTO

    /**
     * The language to transcribe in: null for auto-detect.
     *
     * @param chosen what [encode] produced for this recording, or null when nothing was picked.
     * @param setting the pinned Settings language, itself null for auto-detect.
     *
     * An unrecognised [chosen] falls back to [setting] rather than reaching whisper: input data outlives
     * an install, so a code retired in a later version must not become a language nobody speaks.
     */
    fun resolve(chosen: String?, setting: String?): String? = when {
        chosen == null -> setting
        chosen == AUTO -> null
        chosen in SUPPORTED -> chosen
        else -> setting
    }
}
