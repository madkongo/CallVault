/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.StringRes
import java.text.Collator
import com.baba.callvault.R
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.transcription.TranscriptionLanguageChoice
import com.baba.callvault.transcription.model.TranscriptionModel

/**
 * Labels and option ranges for transcription settings.
 *
 * Kept beside [SyncScheduleLabels] and for the same reason: the wording and the offered values are
 * referenced from more than one place, and a renamed mode or a changed minute step must not be able
 * to drift between them.
 */
object TranscriptionLabels {

    /**
     * Key standing in for "no language", since a dropdown option cannot carry a null key.
     *
     * The same value [TranscriptionLanguageChoice] sends through work input data, and deliberately so:
     * two spellings of "auto" that had to agree would eventually stop agreeing.
     */
    const val AUTO_DETECT_KEY = TranscriptionLanguageChoice.AUTO

    /** Minutes offered for the automatic run — quarter hours, matching the upload schedule. */
    val MINUTE_OPTIONS = listOf(0, 15, 30, 45)

    /**
     * How many recordings one automatic run may take on. 0 is offered as "No limit".
     *
     * Configurable because the right answer depends entirely on how long the calls are: ten short
     * calls is a quick sweep, ten hour-long ones is most of a night at the Best tier.
     */
    val BATCH_LIMIT_OPTIONS = listOf(5, 10, 25, 50, 0)

    /**
     * Languages offered, null meaning auto-detect.
     *
     * Auto-detect is offered but is no longer the default: `AppPreferences.TRANSCRIPTION_LANGUAGE`
     * seeds the pin from the phone's own language, and auto-detect is the fallback for a phone set to a
     * language the app does not offer. It is still shown first — see [sortLanguageOptions].
     *
     * An older comment here claimed it was deliberately *not* the default, because "whisper decodes an
     * unspecified language as English". That was never the model's behaviour — it was this app's bug:
     * `detect_language` was set for auto, which whisper reads as *exit after detecting*, so every
     * auto transcript came back empty. Fixed 2026-08-20 and verified producing Hebrew from a real call.
     *
     * The order here is only the source list; the dropdown sorts it by translated name via
     * [sortLanguageOptions].
     *
     * The codes come from [TranscriptionLanguageChoice.SUPPORTED], which is also what validates a
     * per-recording pick — a list added to here and not there would offer a language the picker then
     * refused to honour.
     */
    val LANGUAGE_OPTIONS: List<String?> = TranscriptionLanguageChoice.SUPPORTED + null

    /**
     * [options] as (key, displayed name), ordered A-Z by the **name**, with auto-detect pinned last.
     *
     * By the name rather than the code, because the names are translated: ordering by "de"/"he"/"zh"
     * would look arbitrary in every language, English included. A [Collator] rather than a plain string
     * sort, because a plain sort files every accented letter after Z — which would leave a French or
     * German list looking broken.
     *
     * Auto-detect is not a language and is pinned **first** rather than filed under "D": it is the one
     * entry that is not a name, so leaving it in the alphabet would hide it somewhere different in every
     * translation.
     */
    fun sortLanguageOptions(options: List<Pair<String, String>>): List<Pair<String, String>> {
        val collator = Collator.getInstance()
        val (auto, languages) = options.partition { it.first == AUTO_DETECT_KEY }
        return auto + languages.sortedWith { a, b -> collator.compare(a.second, b.second) }
    }

    /**
     * Which option the per-recording language dialog opens on, given the pinned Settings [setting].
     *
     * The pinned language, so the common case — this call is in the usual language — is one
     * confirming tap. It falls back to auto-detect when the pin names a language this build does not
     * offer, which is not hypothetical: a setting written by a later version outlives an install, and
     * the alternative is a dropdown showing one language while the confirm button sends another.
     *
     * [offered] is passed in rather than read from [LANGUAGE_OPTIONS] so the fallback is tested
     * against a list, not against whatever the catalogue happens to hold today.
     */
    fun preselectedLanguageKey(setting: String?, offered: List<String?> = LANGUAGE_OPTIONS): String {
        val key = TranscriptionLanguageChoice.encode(setting)
        val keys = offered.map { TranscriptionLanguageChoice.encode(it) }
        return if (key in keys) key else AUTO_DETECT_KEY
    }

    @StringRes
    fun titleOf(mode: TranscriptionMode): Int = when (mode) {
        TranscriptionMode.MANUAL -> R.string.transcription_mode_manual
        TranscriptionMode.AFTER_EACH_CALL -> R.string.transcription_mode_after_each_call
        TranscriptionMode.AUTOMATIC -> R.string.transcription_mode_automatic
    }

    /**
     * The tier's name in the picker.
     *
     * Exhaustive on purpose and worth keeping that way: this `when` is the only thing in the app
     * that fails to compile when a model is added to the catalogue, and it is what forced the
     * wizard's hand-written two-model note to be found and fixed.
     *
     * The wording ranks the two large tiers by what they cost rather than by quality, because they
     * are the *same model* at different precision and q8_0 measured both faster and no less
     * accurate — calling q5_0 "Best quality" and q8_0 something better would be describing a
     * difference that is not there. What separates them is the download.
     */
    @StringRes
    fun titleOf(model: TranscriptionModel): Int = when (model) {
        TranscriptionModel.SMALL_Q5_1 -> R.string.transcription_model_small
        TranscriptionModel.LARGE_V3_TURBO_Q5_0 -> R.string.transcription_model_best_small_download
        TranscriptionModel.LARGE_V3_TURBO_Q8_0 -> R.string.transcription_model_best
    }

    /** Maps a language code (or null for auto-detect) to its label. */
    @StringRes
    fun languageOf(code: String?): Int = when (code) {
        "he" -> R.string.transcription_language_hebrew
        "en" -> R.string.transcription_language_english
        "ar" -> R.string.transcription_language_arabic
        "zh" -> R.string.transcription_language_chinese
        "fr" -> R.string.transcription_language_french
        "de" -> R.string.transcription_language_german
        "hu" -> R.string.transcription_language_hungarian
        "it" -> R.string.transcription_language_italian
        "pl" -> R.string.transcription_language_polish
        "pt" -> R.string.transcription_language_portuguese
        "ru" -> R.string.transcription_language_russian
        "es" -> R.string.transcription_language_spanish
        "vi" -> R.string.transcription_language_vietnamese
        else -> R.string.transcription_language_auto
    }
}
