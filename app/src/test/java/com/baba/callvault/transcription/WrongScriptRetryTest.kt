/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A language pinned to one alphabet and a transcript written in another is a failed decode, and it is
 * the one failure of whisper's that can be seen without understanding a word.
 *
 * The first case is the maintainer's OP12 call of 2026-09-20, verbatim: Hebrew pinned, `lang=he` in the
 * log, no prompt — and "בדיקה, בדיקה… וואן פלוס 12" came back as English on two different phones.
 */
class WrongScriptRetryTest {

    private fun said(vararg lines: String) = lines.mapIndexed { i, text -> TranscriptSegment(i * 1_000L, (i + 1) * 1_000L, text) }

    @Test
    fun `hebrew pinned and english out is the wrong script`() {
        val measured = said("God, God, God, God, God is 1 plus 12.", "God, God, God, God is 1 plus 10.")

        assertTrue(WrongScriptRetry.isWrongScript(measured, "he"))
    }

    @Test
    fun `hebrew with foreign words in it is still hebrew`() {
        // Real Hebrew calls are full of brand names and English. This is the transcript that must never retry.
        assertFalse(WrongScriptRetry.isWrongScript(said("בדיקה, בדיקה, בדיקה זה 1 plus 12,", "בדיקה, זה OnePlus 9."), "he"))
    }

    @Test
    fun `the same check holds for the other non-latin languages on offer`() {
        assertTrue(WrongScriptRetry.isWrongScript(said("Hello there, how are you doing today?"), "ru"))
        assertTrue(WrongScriptRetry.isWrongScript(said("Hello there, how are you doing today?"), "ar"))
        assertFalse(WrongScriptRetry.isWrongScript(said("Привет, как у тебя дела сегодня?"), "ru"))
    }

    @Test
    fun `a latin language and auto-detect are never judged`() {
        // English pinned and Hebrew out would be the mirror image, but it has never been seen, and every
        // Latin-script language shares one alphabet — there is nothing here a script can tell apart.
        assertFalse(WrongScriptRetry.isWrongScript(said("בדיקה בדיקה בדיקה"), "en"))
        assertFalse(WrongScriptRetry.isWrongScript(said("God, God, God is 1 plus 12."), null))
    }

    @Test
    fun `too few letters to judge is not a verdict`() {
        assertFalse(WrongScriptRetry.isWrongScript(said("OK.", "12 34 56"), "he"))
        assertFalse(WrongScriptRetry.isWrongScript(emptyList(), "he"))
    }

    @Test
    fun `only a short recording is decoded again`() {
        val english = said("God, God, God, God, God is 1 plus 12.")

        assertTrue(WrongScriptRetry.shouldRetry(english, "he", audioMs = 12_000))
        // A retry costs the whole decode again. Short clips are where whisper has the least to go on and
        // tips over; an hour of English under a Hebrew pin is a wrong setting, not a knife-edge.
        assertFalse(WrongScriptRetry.shouldRetry(english, "he", audioMs = WrongScriptRetry.MAX_RETRY_AUDIO_MS + 1))
    }

    @Test
    fun `the first fallback that comes back in the right script wins`() = runBlocking<Unit> {
        val english = said("God, God, God is 1 plus 12.")
        val hebrew = said("בדיקה, בדיקה, בדיקה זה 1 פלוס 12.")
        val tried = mutableListOf<DecodeSettings>()

        val chosen = WrongScriptRetry.recover(english, "he") { settings ->
            tried += settings
            if (tried.size == 2) hebrew else english
        }

        assertEquals(hebrew, chosen)
        assertEquals(WrongScriptRetry.FALLBACKS.take(2), tried)
    }

    @Test
    fun `when nothing recovers it, the first transcript is kept rather than the last`() = runBlocking<Unit> {
        // Every fallback is a worse default than the default. Ending on one of them, still in the wrong
        // script, would trade a bad transcript for a worse one.
        val english = said("God, God, God is 1 plus 12.")

        val chosen = WrongScriptRetry.recover(english, "he") { said("Still English, I am afraid, every time.") }

        assertEquals(english, chosen)
    }

    @Test
    fun `a fallback that throws is skipped, not fatal`() = runBlocking<Unit> {
        val english = said("God, God, God is 1 plus 12.")

        val chosen = WrongScriptRetry.recover(english, "he") { error("decode failed") }

        assertEquals(english, chosen)
    }

    @Test
    fun `a stop during a retry is a stop, not a failed fallback`() {
        // Swallowed, the run would carry on decoding a recording the user just told it to leave alone.
        val english = said("God, God, God is 1 plus 12.")

        val outcome = runCatching {
            runBlocking { WrongScriptRetry.recover(english, "he") { throw kotlinx.coroutines.CancellationException("stopped") } }
        }

        assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
