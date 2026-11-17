/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.transcripts.RecordingSearch.ContentHit
import com.baba.callvault.data.transcripts.RecordingSearch.Kind
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.summary.CallSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one search line on the Recordings page: the order (name, tag, summary, transcript), one row
 * per recording, the excerpt for text hits, and that a big library is answered by the full-text
 * indexes alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingSearchTest {

    private lateinit var db: TranscriptDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun excerpt(text: String) = PageSearch.excerpt(text, "invoice")
    private fun hit(summary: String? = null, transcript: String? = null) =
        ContentHit(summary?.let(::excerpt), transcript?.let(::excerpt))

    @Test
    fun `name beats tag beats summary beats transcript`() {
        val library = listOf("transcript.ogg", "summary.ogg", "tag.ogg", "name.ogg")
        val tags = mapOf("tag.ogg" to setOf("invoice"))
        val content = mapOf(
            "summary.ogg" to hit(summary = "the invoice is late"),
            "transcript.ogg" to hit(transcript = "send me the invoice"),
        )

        val ranked = RecordingSearch.ranked(library) { name ->
            RecordingSearch.classify("invoice", nameMatches = name == "name.ogg", tags[name], content[name])
        }

        assertEquals(listOf("name.ogg", "tag.ogg", "summary.ogg", "transcript.ogg"), ranked.map { it.first })
        assertEquals(listOf(Kind.NAME, Kind.TAG, Kind.SUMMARY, Kind.TRANSCRIPT), ranked.map { it.second.kind })
    }

    @Test
    fun `a recording matching in every place is listed once under its best reason`() {
        val why = RecordingSearch.classify(
            "invoice", nameMatches = false, tags = setOf("Invoice"),
            content = hit(summary = "invoice", transcript = "invoice"),
        )
        assertEquals(Kind.TAG, why!!.kind)

        // One reason per recording, so a recording that matches in all four places is one row.
        val ranked = RecordingSearch.ranked(listOf("a.ogg")) { why }
        assertEquals(1, ranked.size)
        assertEquals(Kind.TAG, ranked.single().second.kind)
    }

    @Test
    fun `equal rank keeps the incoming order so the page sort still decides`() {
        val newestFirst = listOf("c.ogg", "b.ogg", "a.ogg")
        val ranked = RecordingSearch.ranked(newestFirst) { RecordingSearch.classify("x", true, null, null) }
        assertEquals(newestFirst, ranked.map { it.first })
    }

    @Test
    fun `a tag hit shows the tag with the typed part marked`() {
        val why = RecordingSearch.classify("voic", nameMatches = false, tags = setOf("work", "Voicemail"), content = null)!!

        assertEquals(Kind.TAG, why.kind)
        val found = why.excerpt!!
        assertEquals("Voicemail", found.text)
        assertEquals("Voic", found.text.substring(found.matchStart, found.matchEnd))
    }

    @Test
    fun `a text hit carries a snippet around the word`() {
        val long = "x ".repeat(80) + "the invoice arrived " + "y ".repeat(80)
        val why = RecordingSearch.classify("invoice", false, null, hit(transcript = long))!!

        assertEquals(Kind.TRANSCRIPT, why.kind)
        val found = why.excerpt!!
        assertEquals("invoice", found.text.substring(found.matchStart, found.matchEnd))
        assertTrue(found.text.length < long.length)
    }

    @Test
    fun `no match anywhere is no result`() {
        assertNull(RecordingSearch.classify("invoice", false, setOf("work"), null))
        assertNull(RecordingSearch.classify("invoice", false, null, ContentHit(null, null)))
    }

    @Test
    fun `a name match shows no extra line`() {
        val why = RecordingSearch.classify("anna", nameMatches = true, tags = setOf("anna"), content = hit(summary = "anna"))!!
        assertEquals(Kind.NAME, why.kind)
        assertNull(why.excerpt)
    }

    @Test
    fun `content search over a large library answers from the indexes`() = runBlocking {
        val total = 3000
        // 3000 recordings: every one has a transcript line, one in 100 mentions the needle there,
        // one in 250 has it in a summary too. Everything else is filler no query should read.
        val segments = (0 until total).map { i ->
            val text = if (i % 100 == 0) "please pay the zebrafish bill soon" else "ordinary chat number $i about nothing"
            TranscriptSegmentEntry(displayName = "call-$i.ogg", startMs = 0, endMs = 1000, text = text)
        }
        db.transcriptDao().insertSegments(segments)
        (0 until total step 250).forEach { i ->
            val doc = CallSummary(
                intent = "Zebrafish bill", summary = "He will settle the zebrafish bill.",
                keyPoints = emptyList(), decisions = emptyList(), actionItems = emptyList(), keyFacts = emptyList(),
            )
            db.summaryDao().upsert(
                CallSummaryEntry(
                    displayName = "call-$i.ogg", document = doc.toJson(), model = "test",
                    createdAt = 0, searchText = doc.searchableText(),
                )
            )
        }

        val started = System.nanoTime()
        val found = TranscriptRepository.searchContent(db, "zebrafish")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // Transcript hits: 30 calls (every 100th). Summary hits: 12 calls (every 250th); the 6 that
        // are multiples of 500 are in both (0, 500, ... 2500) — and 0, 500.. are also multiples of 100.
        val expected = ((0 until total step 100) + (0 until total step 250)).toSet()
        assertEquals(expected, found.keys.map { it.removePrefix("call-").removeSuffix(".ogg").toInt() }.toSet())

        // The excerpt for a text hit holds the word.
        val sample = found["call-100.ogg"]!!
        assertNull(sample.summary)
        val line = sample.transcript!!
        assertEquals("zebrafish", line.text.substring(line.matchStart, line.matchEnd))
        assertNotNull(found["call-250.ogg"]!!.summary)

        // Not a timing assertion that could flake on a slow machine: only that it stays far from
        // a scan-and-parse of every row (which this size would take seconds to do in Kotlin).
        assertTrue("took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `a typed prefix and a stray quote do not break the content search`() = runBlocking {
        db.transcriptDao().insertSegments(
            listOf(TranscriptSegmentEntry(displayName = "a.ogg", startMs = 0, endMs = 1, text = "the invoice is here"))
        )

        assertTrue(TranscriptRepository.searchContent(db, "invo").containsKey("a.ogg"))
        // Must not throw: a quote typed naturally is not an FTS syntax error.
        TranscriptRepository.searchContent(db, "it's \"invoice")
        assertTrue(TranscriptRepository.searchContent(db, "   ").isEmpty())
    }
}
