/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Suggested tags from the summary model (BabApps item 136): grammar, prompt, parser and the dismissed list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SummaryTagSuggestionTest {

    private val doc = """{"intent":"i","summary":"s","keyPoints":[],"decisions":[],"actionItems":[],"keyFacts":[]"""

    private fun segment(text: String) = TranscriptSegmentEntry(
        id = 1, displayName = "call", startMs = 0, endMs = 1000, text = text, speaker = null
    )

    // ---- grammar ----

    @Test
    fun `the tags grammar adds tags as the last key and leaves the other keys alone`() {
        val root = SummaryGrammar.JSON_WITH_TAGS.lines().single { it.startsWith("root ::=") }
        assertTrue(root.indexOf("\\\"keyFacts\\\"") < root.indexOf("\\\"tags\\\""))
        assertTrue("tags must be the last key", root.trimEnd().endsWith("\\\"tags\\\":\" ws tags ws \"}\""))
        // The shared lines are byte-for-byte the plain grammar's: lists, strings and whitespace.
        SummaryGrammar.JSON.lines().filter { it.startsWith("array ::=") || it.startsWith("item ::=") }
            .forEach { assertTrue(SummaryGrammar.JSON_WITH_TAGS.lines().contains(it)) }
        assertNotEquals(SummaryGrammar.JSON, SummaryGrammar.JSON_WITH_TAGS)
    }

    @Test
    fun `the tag rules are bounded by the constants, with no unbounded repetition and no newline`() {
        val rules = SummaryGrammar.JSON_WITH_TAGS.lines().filter { it.isNotBlank() }
        rules.forEach { assertTrue("not a one-line rule: $it", it.contains("::=")) }
        val tags = rules.single { it.startsWith("tags ::=") }
        val tag = rules.single { it.startsWith("tag ::=") }
        val tchar = rules.single { it.startsWith("tchar ::=") }

        assertTrue(tags.contains("{0,${CallSummary.MAX_TAGS - 1}}"))
        assertTrue(tag.contains("tchar{1,${CallSummary.MAX_TAG_CHARS}}"))
        assertTrue("a control character (so a newline) must be excluded", tchar.contains("\\x00-\\x1F"))
        assertTrue("a quote or backslash must be excluded", tchar.contains("[^\"\\\\"))
        assertFalse(rules.any { Regex("""[a-z\]]\*""").containsMatchIn(it) })
        Regex("""\{\d+,(\d+)}""").findAll(SummaryGrammar.JSON_WITH_TAGS)
            .forEach { assertTrue(it.groupValues[1].toInt() < 2_000) }
    }

    // ---- prompt ----

    @Test
    fun `the tags line is the last instruction before the transcript or the parts`() {
        val chunk = SummaryPrompt.forChunkJson(listOf(segment("hello")), "he", withTimestamps = true, withTags = true)
        val before = chunk.substringBefore("Transcript:").trimEnd().lines().last()
        assertEquals(SummaryPrompt.tagsLine("he"), before)

        val merge = SummaryPrompt.forMergeJson(listOf("""{"intent":"a"}"""), "he")
        assertEquals(SummaryPrompt.tagsLine("he"), merge.substringBefore("Part 1:").trimEnd().lines().last())
    }

    @Test
    fun `a chunk prompt without tags is exactly what it was before`() {
        val plain = SummaryPrompt.forChunkJson(listOf(segment("hello")), "en", withTimestamps = true)
        assertFalse(plain.contains("\"tags\""))
        val tagged = SummaryPrompt.forChunkJson(listOf(segment("hello")), "en", withTimestamps = true, withTags = true)
        assertEquals(plain.lines().filterNot { it == SummaryPrompt.tagsLine("en") }, tagged.lines().filterNot { it == SummaryPrompt.tagsLine("en") })
    }

    @Test
    fun `tags are asked for in the resolved summary language, which can differ from the call language`() {
        // The prompt only ever receives the resolved summary language (the call may be Hebrew and the
        // setting English); the tags line must name that language and nothing else.
        val line = SummaryPrompt.tagsLine("en")
        assertTrue(line.contains("in English"))
        assertFalse(line.contains("Hebrew"))
        assertTrue(SummaryPrompt.tagsLine("he").contains("in Hebrew"))
        assertTrue(SummaryPrompt.tagsLine(null).contains("language of the summary"))
        val prompt = SummaryPrompt.forChunkJson(listOf(segment("שלום")), "en", withTimestamps = true, withTags = true)
        assertTrue(prompt.contains(line))
    }

    // ---- parser ----

    @Test
    fun `a summary written before tags existed loads, with nothing to suggest`() {
        val parsed = CallSummary.parse("$doc}")!!
        assertEquals(emptyList<String>(), parsed.tags)
        assertEquals(emptyList<String>(), parsed.suggestedTags(emptyList()))
    }

    @Test
    fun `tags are cleaned, de-duplicated and limited`() {
        val parsed = CallSummary.parse("""$doc,"tags":["  Plumbing ","plumbing","","Boiler","Leak","Extra",""]}""")!!
        assertEquals(listOf("Plumbing", "Boiler", "Leak"), parsed.tags)
    }

    @Test
    fun `a Hebrew tag round-trips and merges with the user's own spelling through canonical`() {
        val parsed = CallSummary.parse("""$doc,"tags":["אינסטלציה"]}""")!!
        assertEquals(listOf("אינסטלציה"), CallSummary.parse(parsed.toJson())!!.tags)
        // Accepting goes through TagName.canonical, as a typed tag does: an existing spelling wins.
        val stored = com.baba.callvault.data.transcripts.TagName.canonical("plumbing", listOf("Plumbing"))
        assertEquals("Plumbing", stored)
        assertEquals(emptyList<String>(), CallSummary.parse("""$doc,"tags":["plumbing"]}""")!!.suggestedTags(listOf("Plumbing")))
    }

    @Test
    fun `suggestions leave out tags the call already has and ones the user dismissed`() {
        val s = CallSummary.parse("""$doc,"tags":["A","B","C"]}""")!!.withDismissed("b")
        assertEquals(listOf("A", "C"), s.suggestedTags(emptyList()))
        assertEquals(listOf("C"), s.suggestedTags(listOf("a")))
        assertEquals(s, s.withDismissed("B"))
    }

    @Test
    fun `dismissed tags survive being stored and loaded, and carry to a new summary`() {
        val old = CallSummary.parse("""$doc,"tags":["A"]}""")!!.withDismissed("Gone")
        val reloaded = CallSummary.parse(old.toJson())!!
        assertEquals(listOf("Gone"), reloaded.dismissedTags)

        val fresh = CallSummary.parse("""$doc,"tags":["gone","New"]}""")!!.inheritingDismissed(reloaded.dismissedTags)
        assertEquals(listOf("New"), fresh.suggestedTags(emptyList()))
    }

    @Test
    fun `suggested tags are not part of the search text`() {
        val s = CallSummary.parse("""$doc,"tags":["Secretword"]}""")!!
        assertFalse(s.searchableText().contains("Secretword"))
    }
}
