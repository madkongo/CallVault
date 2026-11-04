/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionLanguageChoiceTest {

    @Test
    fun no_choice_falls_back_to_the_setting() {
        assertEquals("he", TranscriptionLanguageChoice.resolve(chosen = null, setting = "he"))
    }

    @Test
    fun no_choice_and_no_setting_is_auto_detect() {
        assertNull(TranscriptionLanguageChoice.resolve(chosen = null, setting = null))
    }

    @Test
    fun a_choice_wins_over_the_setting() {
        assertEquals("en", TranscriptionLanguageChoice.resolve(chosen = "en", setting = "he"))
    }

    @Test
    fun auto_detect_can_be_chosen_over_a_pinned_setting() {
        // The one case a plain nullable string cannot carry: "chose auto" and "chose nothing" would
        // both be null, and the setting would quietly win.
        val chosen = TranscriptionLanguageChoice.encode(null)

        assertNull(TranscriptionLanguageChoice.resolve(chosen = chosen, setting = "he"))
    }

    @Test
    fun encoding_a_language_round_trips_it() {
        val chosen = TranscriptionLanguageChoice.encode("ar")

        assertEquals("ar", TranscriptionLanguageChoice.resolve(chosen = chosen, setting = "he"))
    }

    @Test
    fun a_supported_device_language_becomes_the_default_pin() {
        assertEquals("es", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("es-MX")))
        assertEquals("en", TranscriptionLanguageChoice.defaultLanguage(Locale.US))
    }

    @Test
    fun a_persian_phone_pins_persian() {
        // #55: Persian was not on offer, so a Persian phone fell to auto-detect, which heard Arabic.
        assertEquals("fa", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("fa-IR")))
    }

    @Test
    fun every_language_the_app_is_translated_into_can_be_pinned() {
        // The second request of this kind (#46 Greek, #55 Persian) came from a user whose app was
        // already in their language. If CallVault speaks it, transcription must be able to pin it.
        val uiLanguages = listOf("ar", "de", "el", "en", "es", "fa", "fr", "he", "hu", "it", "pl", "pt", "ru", "uk", "vi", "zh")
        uiLanguages.forEach { assertTrue("$it cannot be pinned", it in TranscriptionLanguageChoice.SUPPORTED) }
    }

    @Test
    fun ukrainian_is_supported_as_a_device_default_and_explicit_choice() {
        assertEquals("uk", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("uk-UA")))
        assertEquals("uk", TranscriptionLanguageChoice.resolve(chosen = "uk", setting = "en"))
    }

    @Test
    fun bengali_is_supported_as_a_device_default_and_explicit_choice() {
        assertEquals("bn", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("bn-BD")))
        assertEquals("bn", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("bn-IN")))
        assertEquals("bn", TranscriptionLanguageChoice.resolve(chosen = "bn", setting = "en"))
    }

    @Test
    fun urdu_is_supported_as_a_device_default_and_explicit_choice() {
        assertEquals("ur", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("ur-PK")))
        assertEquals("ur", TranscriptionLanguageChoice.resolve(chosen = "ur", setting = "en"))
    }

    @Test
    fun an_unsupported_device_language_falls_back_to_auto_detect() {
        // Danish has no label to show for it, so there is nothing honest to pin. Auto-detect is the
        // fallback here rather than the default everywhere.
        assertNull(TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("da-DK")))
        assertNull(TranscriptionLanguageChoice.defaultLanguage(Locale.ROOT))
    }

    @Test
    fun a_hebrew_phone_pins_hebrew_despite_the_locale_reporting_iw() {
        // Locale still reports the 1989 code. Left unmapped it would miss SUPPORTED and send exactly
        // the users this defaulting exists for straight back to auto-detect.
        assertEquals("he", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("he-IL")))
        assertEquals("he", TranscriptionLanguageChoice.defaultLanguage(Locale.forLanguageTag("iw-IL")))
    }

    @Test
    fun the_default_pin_is_never_a_language_the_picker_cannot_show() {
        // A default outside SUPPORTED would leave the dropdown falling back to its first entry, so
        // Settings would claim auto-detect while whisper ran on something else entirely.
        for (locale in Locale.getAvailableLocales()) {
            val pinned = TranscriptionLanguageChoice.defaultLanguage(locale)

            if (pinned != null) {
                assertEquals(
                    "$locale pinned $pinned, which the picker does not offer",
                    true,
                    pinned in TranscriptionLanguageChoice.SUPPORTED,
                )
            }
        }
    }

    @Test
    fun an_unknown_code_is_ignored_rather_than_sent_to_whisper() {
        // Work input data outlives an install: a code retired in a later version must not become a
        // language whisper has never heard of.
        assertEquals("he", TranscriptionLanguageChoice.resolve(chosen = "klingon", setting = "he"))
    }
}
