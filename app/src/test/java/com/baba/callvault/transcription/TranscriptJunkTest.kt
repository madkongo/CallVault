/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 139: a Hindi call came back as "foreign" in both segments and was saved as a transcript. */
class TranscriptJunkTest {

    private fun said(vararg lines: String) = lines.mapIndexed { i, text -> TranscriptSegment(i * 1000L, i * 1000L + 900, text) }

    @Test
    fun foreign_repeated_fourteen_times_is_junk() {
        val found = TranscriptJunk.find(said(List(7) { "foreign" }.joinToString(" "), List(7) { "foreign" }.joinToString(" ")))
        assertNotNull(found)
        assertEquals(TranscriptJunk.Kind.REPEATED_TOKEN, found!!.kind)
        assertEquals("foreign", found.token)
    }

    @Test
    fun a_single_bare_foreign_is_junk() {
        assertEquals("foreign", TranscriptJunk.find(said("Foreign."))!!.token)
    }

    @Test
    fun only_whisper_tags_is_junk() {
        assertEquals(TranscriptJunk.Kind.TAGS_ONLY, TranscriptJunk.find(said("[Music]"))!!.kind)
        assertEquals(TranscriptJunk.Kind.TAGS_ONLY, TranscriptJunk.find(said("[foreign]", "(speaking in foreign language)"))!!.kind)
        assertEquals(TranscriptJunk.Kind.TAGS_ONLY, TranscriptJunk.find(said("♪♪ [Music] ♪♪"))!!.kind)
    }

    @Test
    fun one_token_in_nine_of_ten_words_is_junk() {
        val text = "thank thank thank thank thank thank thank thank thank you"
        assertEquals("thank", TranscriptJunk.find(said(text))!!.token)
    }

    @Test
    fun normal_text_passes() {
        assertNull(TranscriptJunk.find(said("Hello there, how are you doing today?", "I am fine, thank you, and you?")))
        assertNull(TranscriptJunk.find(said("שלום, מה שלומך היום? הכול בסדר, תודה רבה לך")))
        assertNull(TranscriptJunk.find(said("नमस्ते, आप कैसे हैं? मैं ठीक हूँ, धन्यवाद")))
    }

    @Test
    fun short_real_replies_pass() {
        assertNull(TranscriptJunk.find(said("OK.")))
        assertNull(TranscriptJunk.find(said("Yes yes, bye")))
        assertNull(TranscriptJunk.find(said("No no no no")))
        assertNull(TranscriptJunk.find(said("12 34 56")))
    }

    @Test
    fun a_real_call_with_a_tag_among_the_words_passes() {
        assertNull(TranscriptJunk.find(said("[Music]", "Hello, are you there? I called about the invoice.")))
    }

    @Test
    fun an_empty_or_blank_transcript_is_not_this_guards_case() {
        assertNull(TranscriptJunk.find(emptyList()))
        assertNull(TranscriptJunk.find(said("  ")))
    }

    @Test
    fun a_loop_needs_both_enough_words_and_enough_share() {
        // Seven words is short; eight with a quarter different is a real, repetitive sentence.
        assertNull(TranscriptJunk.find(said("no no no no no no no")))
        assertNull(TranscriptJunk.find(said("go go go go go go go now please then")))
        assertNotNull(TranscriptJunk.find(said("go go go go go go go go")))
    }

    @Test
    fun recover_returns_the_first_fallback_that_gives_words_and_skips_junk() = runBlocking {
        val tried = mutableListOf<DecodeSettings>()
        val chosen = TranscriptJunk.recover("en") { settings ->
            tried += settings
            if (tried.size < 2) said("foreign foreign foreign foreign foreign foreign foreign foreign") else said("Hello, this is real.")
        }
        assertEquals("Hello, this is real.", chosen!!.single().text)
        assertEquals(WrongScriptRetry.FALLBACKS.take(2), tried)
    }

    @Test
    fun recover_gives_null_when_every_fallback_is_junk_or_fails() = runBlocking {
        var calls = 0
        val chosen = TranscriptJunk.recover("en") {
            if (++calls == 1) error("decode failed") else said("[Music]")
        }
        assertNull(chosen)
        assertEquals(WrongScriptRetry.FALLBACKS.size, calls)
    }

    @Test
    fun recover_never_swallows_a_stop() {
        var thrown = false
        try {
            runBlocking { TranscriptJunk.recover("en") { throw CancellationException("stopped") } }
        } catch (e: CancellationException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
