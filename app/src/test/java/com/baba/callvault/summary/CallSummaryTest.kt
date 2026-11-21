/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The parser's job is to be the one place a model's output stops being text and starts being data.
 *
 * Everything here is about refusing to store half a summary. The grammar makes malformed JSON
 * unsampleable, but the grammar is only applied when a model is running under it — a summary read
 * back from an older row, or produced by a run where the grammar failed to compile, must not reach
 * the screen as a half-populated card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallSummaryTest {

    private val wellFormed = """
        {
          "intent": "Chasing an unpaid invoice",
          "summary": "The caller rang about invoice 4021, unpaid since June.",
          "keyPoints": ["Invoice 4021 is overdue", "It was sent to the wrong address"],
          "decisions": ["[1:30] Resend the invoice to accounts@"],
          "actionItems": ["[2:15] Call back on Thursday if unpaid"],
          "keyFacts": ["Invoice 4021", "£1,240"]
        }
    """.trimIndent()

    @Test
    fun `parses the shape the grammar produces`() {
        val parsed = CallSummary.parse(wellFormed)!!

        assertEquals("Chasing an unpaid invoice", parsed.intent)
        assertEquals("The caller rang about invoice 4021, unpaid since June.", parsed.summary)
        assertEquals(2, parsed.keyPoints.size)
        assertEquals("[1:30] Resend the invoice to accounts@", parsed.decisions.single())
        assertEquals("[2:15] Call back on Thursday if unpaid", parsed.actionItems.single())
        assertEquals(listOf("Invoice 4021", "£1,240"), parsed.keyFacts)
    }

    @Test
    fun `returns null when the cut took the prose with it`() {
        // Measured: under a token cap a small model drops the closing quote and brace. A sentence
        // that stops mid-word is not a summary, and a card built from one claims the call was
        // summarised. Closing the object by hand does not change that — there is nothing to close
        // back to.
        val truncated = """{"intent": "Chasing an invoice", "summary": "The caller rang about"""

        assertNull(CallSummary.parse(truncated))
    }

    @Test
    fun `keeps what a truncated answer did finish rather than losing the chunk`() {
        // keyFacts is required and the fixed key order puts it last, so an answer that hit its token
        // budget used to cost the entire chunk over a missing brace — worst in Hebrew and Arabic,
        // where the same content costs more tokens. Everything before the cut was written under the
        // grammar and is worth keeping; the item being written when the budget ran out is not.
        val truncated = """
            {"intent": "Chasing an unpaid invoice",
             "summary": "The caller rang about invoice 4021.",
             "keyPoints": ["Invoice 4021 is overdue"], "decisions": [], "actionItems": [],
             "keyFacts": ["Invoice 4021", "£1,2
        """.trimIndent()

        val parsed = CallSummary.parse(truncated)!!

        assertEquals("Chasing an unpaid invoice", parsed.intent)
        assertEquals("The caller rang about invoice 4021.", parsed.summary)
        assertEquals(listOf("Invoice 4021 is overdue"), parsed.keyPoints)
        assertEquals("the half-written item must not be shown", listOf("Invoice 4021"), parsed.keyFacts)
    }

    @Test
    fun `a list the cut never reached is empty rather than missing`() {
        // [] is exactly what the prompt asks for when there is nothing, so a list the generation
        // never got to is honestly empty. Nothing is invented to fill it.
        val truncated = """{"intent": "A wrong number", "summary": "Someone dialled wrong.", "key"""

        val parsed = CallSummary.parse(truncated)!!

        assertTrue(parsed.keyPoints.isEmpty())
        assertTrue(parsed.keyFacts.isEmpty())
    }

    @Test
    fun `returns null when a required key is missing`() {
        val missingActionItems = """
            {"intent": "a", "summary": "b", "keyPoints": [], "decisions": [], "keyFacts": []}
        """.trimIndent()

        assertNull(CallSummary.parse(missingActionItems))
    }

    @Test
    fun `returns null when the text holds no object at all`() {
        assertNull(CallSummary.parse("I'm sorry, I cannot summarise this call."))
        assertNull(CallSummary.parse(""))
    }

    @Test
    fun `ignores keys it does not know`() {
        // `sentiment` and `participants` were dropped from the grammar deliberately. A model asked
        // for them by an older prompt, or a future one that adds a field, must not break the parse.
        val extra = wellFormed.replaceFirst("{", """{"sentiment": "neutral", "participants": ["A"],""")

        val parsed = CallSummary.parse(extra)!!

        assertEquals("Chasing an unpaid invoice", parsed.intent)
    }

    @Test
    fun `finds the object when the model wraps it in prose or a code fence`() {
        val fenced = "Here is the summary:\n```json\n$wellFormed\n```\nHope that helps."

        val parsed = CallSummary.parse(fenced)!!

        assertEquals("Chasing an unpaid invoice", parsed.intent)
    }

    @Test
    fun `strips a reasoning block before parsing`() {
        val thinking = "<think>Let me analyse the request. Correction: I should re-read it.</think>\n$wellFormed"

        val parsed = CallSummary.parse(thinking)!!

        assertEquals("Chasing an unpaid invoice", parsed.intent)
    }

    @Test
    fun `rejects a summary whose prose is empty`() {
        // Every list may legitimately be empty — a call can decide nothing. The prose cannot: a card
        // with no sentence in it is a blank card, and storing one costs the user another 90 seconds
        // to find that out.
        val blank = wellFormed.replaceFirst(
            "\"summary\": \"The caller rang about invoice 4021, unpaid since June.\"",
            "\"summary\": \"   \""
        )

        assertNull(CallSummary.parse(blank))
    }

    @Test
    fun `accepts empty lists`() {
        val nothingDecided = """
            {"intent": "A wrong number", "summary": "Someone dialled the wrong number and rang off.",
             "keyPoints": [], "decisions": [], "actionItems": [], "keyFacts": []}
        """.trimIndent()

        val parsed = CallSummary.parse(nothingDecided)!!

        assertTrue(parsed.decisions.isEmpty())
        assertTrue(parsed.actionItems.isEmpty())
    }

    @Test
    fun `drops blank entries from lists`() {
        val withBlanks = wellFormed.replaceFirst(
            """["Invoice 4021", "£1,240"]""",
            """["Invoice 4021", "", "   ", "£1,240"]"""
        )

        val parsed = CallSummary.parse(withBlanks)!!

        assertEquals(listOf("Invoice 4021", "£1,240"), parsed.keyFacts)
    }

    @Test
    fun `survives a round trip through storage`() {
        val parsed = CallSummary.parse(wellFormed)!!

        val restored = CallSummary.parse(parsed.toJson())!!

        assertEquals(parsed, restored)
    }

    @Test
    fun `round trips text that would break a delimiter`() {
        // Quotation marks, commas and newlines all appear in real speech. A list encoded by joining
        // on a separator would corrupt here; JSON escapes it.
        val awkward = wellFormed.replaceFirst(
            """["Invoice 4021", "£1,240"]""",
            """["He said \"pay it, or else\"", "line one\nline two"]"""
        )

        val parsed = CallSummary.parse(awkward)!!
        val restored = CallSummary.parse(parsed.toJson())!!

        assertEquals(listOf("He said \"pay it, or else\"", "line one\nline two"), restored.keyFacts)
    }

    @Test
    fun `parseComplete refuses a document that was cut off, where parse repairs it`() {
        val cut = """{"intent":"a","summary":"The caller rang about invoice 4021.","keyPoints":["Invoice 4021 is overdue"],"decisions":[],"actionItems":[],"keyFacts":["Invoice 4021","£1,2"""
        assertNull(CallSummary.parseComplete(cut))
        assertEquals("a", CallSummary.parse(cut)?.intent)
    }

    @Test
    fun `parseComplete reads a whole document like parse does`() {
        val whole = """{"intent":"a","summary":"s","keyPoints":[],"decisions":[],"actionItems":[],"keyFacts":[]}"""
        assertEquals(CallSummary.parse(whole), CallSummary.parseComplete(whole))
        assertNull(CallSummary.parseComplete("no json here"))
    }
}
