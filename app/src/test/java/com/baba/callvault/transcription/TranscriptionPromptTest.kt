/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What whisper is told before it decodes a call: the contact's name, and nothing else.
 *
 * Two richer versions were tried and removed — a glossary the user types, and a built-in list of
 * common product names. The second one *worked*, and was still wrong: a long prompt reads to whisper
 * as text the call is continuing from, and it answers with a few enormous segments instead of many
 * small ones. Segments are what speaker labels are made of, so it bought correct spelling by taking
 * the speaker names off the transcript. Measured on a real 45s call: 19 segments unprompted, 21 with
 * the contact's name, 4 with the list.
 *
 * Which is why these tests care about what is *absent* as much as what is present.
 */
class TranscriptionPromptTest {

    @Test
    fun `names the contact so their spelling does not drift between transcripts`() {
        val prompt = TranscriptionPrompt.build(contactName = "Feroza", language = null)

        assertEquals("Feroza.", prompt)
    }

    @Test
    fun `says nothing at all when there is no name to give`() {
        // Silence beats filler. Every prompt costs segmentation, and an empty one buys nothing.
        assertNull(TranscriptionPrompt.build(contactName = null, language = null))
        assertNull(TranscriptionPrompt.build(contactName = "   ", language = null))
    }

    @Test
    fun `drops a contact name that is really a phone number`() {
        // Unsaved contacts fall back to the number, and feeding digits to whisper before a call full
        // of spoken digits invites it to write them down.
        assertNull(TranscriptionPrompt.build(contactName = "+972 50 123 4567", language = null))
    }

    @Test
    fun `stays short enough that whisper will not recite it`() {
        val prompt = TranscriptionPrompt.build(contactName = "x".repeat(400), language = null)!!

        assertTrue("was ${prompt.length}", prompt.length <= TranscriptionPrompt.MAX_CHARS)
    }

    @Test
    fun `leaves out a name written in another script than the pinned language`() {
        // Measured on the OP12 on 2026-09-20: Hebrew pinned, the log said lang=he, and a Hebrew call
        // came out in English — "God, God, God is 1 plus 12" for "בדיקה, בדיקה". The contact was saved
        // as "Feroza". Whisper follows the language of the words it is primed with over the language
        // it is told, so a Latin name quietly turned the pin off. The same call with a contact saved
        // in Hebrew transcribed correctly.
        assertNull(TranscriptionPrompt.build(contactName = "Feroza", language = "he"))
    }

    @Test
    fun `still names a contact written in the pinned language's own script`() {
        assertEquals("פרוזה.", TranscriptionPrompt.build(contactName = "פרוזה", language = "he"))
        assertEquals("Feroza.", TranscriptionPrompt.build(contactName = "Feroza", language = "en"))
        assertEquals("Иван Петров.", TranscriptionPrompt.build(contactName = "Иван Петров", language = "ru"))
    }

    @Test
    fun `the mismatch runs both ways`() {
        assertNull(TranscriptionPrompt.build(contactName = "פרוזה", language = "en"))
        assertNull(TranscriptionPrompt.build(contactName = "محمد", language = "he"))
    }

    @Test
    fun `leaves out a name that mixes scripts, because half of it would still pull the wrong way`() {
        assertNull(TranscriptionPrompt.build(contactName = "Dr כהן", language = "he"))
    }

    @Test
    fun `digits and punctuation in a name are not a script of their own`() {
        assertEquals("פרוזה 2.", TranscriptionPrompt.build(contactName = "פרוזה 2", language = "he"))
        assertEquals("O'Brien-Smith.", TranscriptionPrompt.build(contactName = "O'Brien-Smith", language = "en"))
    }

    @Test
    fun `under auto-detect the name is sent as it always was`() {
        // Not measured either way, so not changed: there is no pinned language to compare against.
        assertEquals("Feroza.", TranscriptionPrompt.build(contactName = "Feroza", language = null))
    }
}
