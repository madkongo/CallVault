/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The summarisation pipeline, without a native library or ninety seconds a case.
 *
 * The model is faked, which is the point: what is worth testing here is what gets *asked* of it and
 * what is done with the answer — how many passes a call takes, whether a stop leaves a half-summary
 * behind, and whether a refusal to parse is stored anyway. None of that needs a real model, and all
 * of it would be untestable if it lived in the worker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SummaryRunnerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() = runBlocking {
        TranscriptDatabase.get(context).transcriptDao().deleteFor(CALL)
        TranscriptDatabase.get(context).summaryDao().deleteFor(CALL)
    }

    @Test
    fun `a short call is one pass and is stored`() = runBlocking {
        giveTranscript(words = listOf("hello there", "about the invoice"))
        val model = FakeModel(json("Chasing an invoice"))

        val summary = runner(model).run(CALL, MODEL_ID, PATH, "en", now = 5L)

        assertEquals("Chasing an invoice", summary?.intent)
        assertEquals("one chunk must not be merged with itself", 1, model.prompts.size)
        val stored = TranscriptDatabase.get(context).summaryDao().summary(CALL)
        assertEquals(MODEL_ID, stored?.model)
        assertEquals(5L, stored?.createdAt)
    }

    @Test
    fun `a long call is summarised in parts and then merged`() = runBlocking {
        giveTranscript(words = List(4) { "x".repeat(SummaryChunking.DEFAULT_MAX_CHARS / 2) })
        val model = FakeModel { if (isMerge(it)) json("The whole call") else json("part") }

        val summary = runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertEquals("The whole call", summary?.intent)
        assertTrue("expected chunks plus a merge", model.prompts.size >= 3)
        assertTrue("the last pass must be the merge", model.prompts.last().contains("consecutive parts"))
    }

    @Test
    fun `the model is loaded once for the whole call`() = runBlocking {
        giveTranscript(words = List(4) { "y".repeat(SummaryChunking.DEFAULT_MAX_CHARS / 2) })
        val model = FakeModel(json("a"))

        runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        // Loading is seconds and gigabytes; paying it per chunk would dominate a long call.
        assertEquals(1, model.loads)
    }

    @Test
    fun `a recording with no transcript is not attempted`() = runBlocking {
        val model = FakeModel(json("never asked"))

        val summary = runner(model).run("never-transcribed.ogg", MODEL_ID, PATH, "en", now = 1L)

        assertNull(summary)
        assertEquals("the model must not be loaded for nothing", 0, model.loads)
    }

    @Test
    fun `stopping between chunks stores nothing at all`() = runBlocking {
        // Half a call summarised is not a summary of the call. Storing it would show the user an
        // account of the first two minutes as though it covered the whole conversation.
        giveTranscript(words = List(4) { "z".repeat(SummaryChunking.DEFAULT_MAX_CHARS / 2) })
        val model = FakeModel(json("part"))
        var generated = 0

        val summary = runner(model).run(
            CALL, MODEL_ID, PATH, "en", now = 1L,
            shouldStop = { generated++ >= 1 }
        )

        assertNull(summary)
        assertNull(TranscriptDatabase.get(context).summaryDao().summary(CALL))
    }

    @Test
    fun `an aborted run stores nothing even though the model returned text`() = runBlocking {
        // An aborted generate returns whatever was produced before the stop. Without consulting the
        // abort flag that is indistinguishable from a very terse summary.
        giveTranscript(words = listOf("hello"))
        val model = FakeModel(json("truncated"))

        val summary = SummaryRunner(context, model, wasAborted = { true })
            .run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertNull(summary)
        assertNull(TranscriptDatabase.get(context).summaryDao().summary(CALL))
    }

    @Test
    fun `output that does not parse is not stored`() = runBlocking {
        giveTranscript(words = listOf("hello"))
        val model = FakeModel("""{"intent": "unterminated""")

        val summary = runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertNull(summary)
        assertNull(TranscriptDatabase.get(context).summaryDao().summary(CALL))
    }

    @Test
    fun `a chunk that ran out of tokens is kept, not thrown away`() = runBlocking {
        // The fixed key order puts keyFacts last, so an answer that hit its budget used to lose the
        // whole chunk over a missing brace — minutes of work the user watched, ending in nothing.
        giveTranscript(words = listOf("hello there"))
        val model = FakeModel(
            """{"intent":"Chasing an invoice","summary":"The caller rang about invoice 4021.",""" +
                """"keyPoints":["Invoice 4021 is overdue"],"decisions":[],"actionItems":[],""" +
                """"keyFacts":["Invoice 4021","£1,2"""
        )

        val summary = runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertEquals("Chasing an invoice", summary?.intent)
        assertEquals(listOf("Invoice 4021"), summary?.keyFacts)
        assertEquals(MODEL_ID, TranscriptDatabase.get(context).summaryDao().summary(CALL)?.model)
    }

    @Test
    fun `a merge that ran out of tokens falls back to the joined parts instead of a repaired fragment`() = runBlocking {
        // C71: a cut-off merge used to be closed by hand into an incomplete summary, which then
        // replaced the complete one the parts could be joined into.
        giveTranscript(words = List(4) { "x".repeat(SummaryChunking.DEFAULT_MAX_CHARS / 2) })
        val truncatedMerge =
            """{"intent":"Cut merge","summary":"Only the start of the merge.","keyPoints":["Half a point""""
        val model = FakeModel { if (isMerge(it)) truncatedMerge else json("part") }

        val summary = runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertEquals("the parts' own intent, not the cut merge's", "part", summary?.intent)
        assertEquals("part", CallSummary.parse(TranscriptDatabase.get(context).summaryDao().summary(CALL)!!.document)?.intent)
    }

    @Test
    fun `a recording deleted while it was being summarised gets no summary`() = runBlocking {
        // C53: the run takes minutes and nothing stopped the result being stored for a call that is gone.
        giveTranscript(words = listOf("hello there"))
        val model = FakeModel {
            runBlocking { TranscriptDatabase.get(context).transcriptDao().deleteFor(CALL) }
            json("orphan")
        }

        val summary = runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertNull(summary)
        assertNull(TranscriptDatabase.get(context).summaryDao().summary(CALL))
    }

    @Test
    fun `a transcript redone while it was being summarised does not get the old words' summary`() = runBlocking {
        giveTranscript(words = listOf("hello there"))
        val model = FakeModel {
            runBlocking {
                TranscriptDatabase.get(context).transcriptDao().upsertTranscript(
                    TranscriptEntry(CALL, TranscriptState.QUEUED, MODEL_ID, "en", 99L, null)
                )
            }
            json("stale")
        }

        val summary = runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertNull(summary)
        assertNull(TranscriptDatabase.get(context).summaryDao().summary(CALL))
    }

    @Test
    fun `every pass is constrained by the grammar`() = runBlocking {
        // The grammar is the only thing making malformed JSON impossible. A pass that forgot it
        // would still usually work, which is exactly why it needs a test rather than vigilance.
        giveTranscript(words = List(4) { "q".repeat(SummaryChunking.DEFAULT_MAX_CHARS / 2) })
        val model = FakeModel(json("a"))

        runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertTrue(model.grammars.isNotEmpty())
        assertTrue(
            "a pass ran unconstrained",
            model.grammars.all { it == SummaryGrammar.JSON || it == SummaryGrammar.JSON_WITH_TAGS }
        )
    }

    // ---- suggested tags (BabApps item 136) ----

    @Test
    fun `a short call asks for tags in its one pass`() = runBlocking {
        giveTranscript(words = listOf("hello there"))
        val model = FakeModel(json("x"))

        runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        assertEquals(listOf(SummaryGrammar.JSON_WITH_TAGS), model.grammars)
        assertTrue(model.prompts.single().contains("\"tags\""))
    }

    @Test
    fun `a long call asks for tags only in the merge, not in the chunks`() = runBlocking {
        giveTranscript(words = List(4) { "x".repeat(SummaryChunking.DEFAULT_MAX_CHARS / 2) })
        val model = FakeModel { if (isMerge(it)) json("whole") else json("part") }

        runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        val chunkIndexes = model.prompts.indices.filter { !isMerge(model.prompts[it]) }
        assertTrue(chunkIndexes.size >= 2)
        chunkIndexes.forEach {
            assertFalse("a chunk prompt asked for tags", model.prompts[it].contains("\"tags\""))
            assertEquals(SummaryGrammar.JSON, model.grammars[it])
        }
        assertTrue(model.prompts.last().contains("\"tags\""))
        assertEquals(SummaryGrammar.JSON_WITH_TAGS, model.grammars.last())
    }

    @Test
    fun `the model's tags are stored with the summary, and a dismissed one does not come back after a redo`() = runBlocking {
        giveTranscript(words = listOf("hello"))
        val withTags = """{"intent":"a","summary":"s","keyPoints":[],"decisions":[],"actionItems":[],"keyFacts":[],"tags":["Plumbing","Boiler"]}"""
        runner(FakeModel(withTags)).run(CALL, MODEL_ID, PATH, "en", now = 1L)
        val first = CallSummary.parse(TranscriptDatabase.get(context).summaryDao().summary(CALL)!!.document)!!
        assertEquals(listOf("Plumbing", "Boiler"), first.tags)

        // The user dismisses "boiler" (different case on purpose), then writes the summary again.
        com.baba.callvault.data.transcripts.TagRepository.dismissSuggestion(context, CALL, "boiler")
        runner(FakeModel(withTags)).run(CALL, MODEL_ID, PATH, "en", now = 2L)

        val second = CallSummary.parse(TranscriptDatabase.get(context).summaryDao().summary(CALL)!!.document)!!
        assertEquals(listOf("Plumbing"), second.suggestedTags(emptyList()))
    }

    @Test
    fun `progress counts chunks so a long call can show something moving`() = runBlocking {
        giveTranscript(words = List(4) { "w".repeat(SummaryChunking.DEFAULT_MAX_CHARS / 2) })
        val model = FakeModel(json("a"))
        val seen = mutableListOf<Pair<Int, Int>>()

        runner(model).run(CALL, MODEL_ID, PATH, "en", now = 1L, onProgress = { done, total ->
            seen += done to total
        })

        assertEquals("the first report must be zero, before any work", 0, seen.first().first)
        assertEquals("the last report must be every chunk", seen.first().second, seen.last().first)
    }

    @Test
    fun `redoing a summary replaces the previous one`() = runBlocking {
        giveTranscript(words = listOf("hello"))
        runner(FakeModel(json("first attempt"))).run(CALL, MODEL_ID, PATH, "en", now = 1L)

        runner(FakeModel(json("second attempt"))).run(CALL, MODEL_ID, PATH, "en", now = 2L)

        val stored = TranscriptDatabase.get(context).summaryDao().summary(CALL)
        assertEquals(2L, stored?.createdAt)
        assertEquals("second attempt", CallSummary.parse(stored!!.document)?.intent)
    }

    // ---- helpers ----

    private fun runner(model: FakeModel) = SummaryRunner(context, model, wasAborted = { false })

    private suspend fun giveTranscript(words: List<String>) {
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(TranscriptEntry(CALL, TranscriptState.DONE, MODEL_ID, "en", 0L, null))
        dao.replaceSegments(
            CALL,
            words.mapIndexed { i, text ->
                TranscriptSegmentEntry(
                    displayName = CALL,
                    startMs = i * 1000L,
                    endMs = (i + 1) * 1000L,
                    text = text,
                    speaker = null
                )
            }
        )
    }

    private fun json(intent: String) = """
        {"intent":"$intent","summary":"Something was said.","keyPoints":[],
         "decisions":[],"actionItems":[],"keyFacts":[]}
    """.trimIndent()

    /**
     * Records what it was asked and answers by *what kind of prompt it is*, not by position.
     *
     * Answering positionally made a test depend on how many chunks the chunker happened to produce,
     * which is a detail it has no business knowing — it silently mismatched the moment the chunk
     * count changed by one.
     */
    private class FakeModel(private val answerFor: (String) -> String) : SummaryModelHost {
        constructor(always: String) : this({ always })

        val prompts = mutableListOf<String>()
        val grammars = mutableListOf<String?>()
        var loads = 0

        override suspend fun run(
            modelPath: String,
            block: suspend (SummarySession) -> CallSummary?
        ): CallSummary? {
            loads++
            return block { prompt, _, grammar ->
                prompts += prompt
                grammars += grammar
                answerFor(prompt)
            }
        }
    }

    /** True for the merge pass, which names itself in its opening line. */
    private fun isMerge(prompt: String) = prompt.contains("consecutive parts")

    private companion object {
        const val CALL = "call.ogg"
        const val MODEL_ID = "gemma-4-e2b-it-q4_k_m"
        const val PATH = "/not/read/by/the/fake"
    }
}
