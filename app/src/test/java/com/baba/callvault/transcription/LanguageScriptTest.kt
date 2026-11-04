/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.R
import com.baba.callvault.ui.common.TranscriptionLabels
import java.lang.Character.UnicodeScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Every pinnable language is wired through everything that treats languages differently.
 *
 * Greek (#46) was added to the picker only: it had no alphabet here (so it counted as Latin, and a Greek
 * contact name could never prime whisper) and no name in the summary prompt. Each check below reads the
 * picker's own list, so the next language added cannot skip one.
 */
class LanguageScriptTest {

    private val expectedScripts = mapOf(
        "he" to UnicodeScript.HEBREW,
        "ar" to UnicodeScript.ARABIC,
        "fa" to UnicodeScript.ARABIC,
        "ur" to UnicodeScript.ARABIC,
        "ru" to UnicodeScript.CYRILLIC,
        "uk" to UnicodeScript.CYRILLIC,
        "zh" to UnicodeScript.HAN,
        "el" to UnicodeScript.GREEK,
        "bn" to UnicodeScript.BENGALI,
    )

    @Test
    fun `every pinnable language is written in the script it really uses`() {
        TranscriptionLanguageChoice.SUPPORTED.forEach { code ->
            assertEquals("script of $code", expectedScripts[code] ?: UnicodeScript.LATIN, LanguageScript.of(code))
        }
    }

    @Test
    fun `every pinnable language has a label of its own`() {
        TranscriptionLanguageChoice.SUPPORTED.forEach { code ->
            assertNotEquals("no label for $code", R.string.transcription_language_auto, TranscriptionLabels.languageOf(code))
        }
    }
}
