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
        // Unknown length is not "short". A container that declares no duration can be an hour long, and
        // tripling that on a guess is the one way this could make a run much worse.
        assertFalse(WrongScriptRetry.shouldRetry(english, "he", audioMs = 0))
        assertFalse(WrongScriptRetry.shouldRetry(english, "he", audioMs = -1))
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
    fun `the fallbacks after a throwing one are still tried`() = runBlocking<Unit> {
        // Throwing on every call cannot tell "skipped and carried on" from "gave up at the first
        // failure"; this one throws in the middle and expects the one after it to be reached.
        val english = said("God, God, God is 1 plus 12.")
        val hebrew = said("בדיקה, בדיקה, בדיקה זה 1 פלוס 12.")
        val tried = mutableListOf<DecodeSettings>()

        val chosen = WrongScriptRetry.recover(english, "he") { settings ->
            tried += settings
            when (tried.size) {
                2 -> error("decode failed")
                3 -> hebrew
                else -> english
            }
        }

        assertEquals(hebrew, chosen)
        assertEquals(WrongScriptRetry.FALLBACKS, tried)
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

    @Test
    fun `a fallback with a third alphabet in it is passed over for a clean one`() = runBlocking<Unit> {
        // Measured on the OP12, 2026-09-20 16:07: the first fallback recovered Hebrew but wrote
        // "זה 1 Behindração שת달ים" where "זה 1 פלוס 12" was said. Mostly Hebrew, so it passed — with a
        // Korean letter in the middle of it. A letter from neither the pinned alphabet nor Latin is never
        // a loanword; it is whisper coming apart.
        val english = said("God, God, God is 1 plus 12.")
        val dirty = said("בדיקה, בדיקה, בדיקה, זה 1 Behindração שת달ים.")
        val clean = said("בדיקה, בדיקה, בדיקה זה 1 פלוס 12.")
        var calls = 0

        val chosen = WrongScriptRetry.recover(english, "he") { if (++calls == 1) dirty else clean }

        assertEquals(clean, chosen)
    }

    @Test
    fun `a dirty recovery still beats the wrong language when nothing clean turns up`() = runBlocking<Unit> {
        val english = said("God, God, God is 1 plus 12.")
        val dirty = said("בדיקה, בדיקה, בדיקה, זה 1 Behindração שת달ים.")

        val chosen = WrongScriptRetry.recover(english, "he") { dirty }

        assertEquals(dirty, chosen)
    }

    @Test
    fun `latin words inside the pinned language are not dirt`() {
        assertFalse(WrongScriptRetry.hasStrayScript(said("בדיקה זה OnePlus 12, OK?"), "he"))
        assertTrue(WrongScriptRetry.hasStrayScript(said("בדיקה זה 1 שת달ים"), "he"))
    }
}
