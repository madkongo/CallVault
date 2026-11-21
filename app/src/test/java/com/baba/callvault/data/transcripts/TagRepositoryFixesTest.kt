/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.summary.CallSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C174 (dismissals that overlap), C164 (tag flows follow the live query) and C148 (Turkish case) on the real repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TagRepositoryFixesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun summaryJson(vararg tags: String) =
        """{"intent":"i","summary":"s","keyPoints":[],"decisions":[],"actionItems":[],"keyFacts":[],"tags":[${tags.joinToString(",") { "\"$it\"" }}]}"""

    @Test
    fun quick_dismissals_that_overlap_are_all_kept() = runBlocking {
        val name = "c174-overlap.ogg"
        val tags = (1..12).map { "tag$it" }
        TranscriptDatabase.get(context).summaryDao()
            .upsert(CallSummaryEntry(name, summaryJson(*tags.toTypedArray()), "m", 1L))

        tags.map { tag -> async(Dispatchers.Default) { TagRepository.dismissSuggestion(context, name, tag) } }.awaitAll()

        val stored = CallSummary.parse(TranscriptDatabase.get(context).summaryDao().summary(name)!!.document)!!
        assertEquals("a dismissal was undone by an overlapping one", tags.toSet(), stored.dismissedTags.toSet())
    }

    @Test
    fun dismissing_a_tag_of_a_call_with_no_summary_is_harmless() = runBlocking {
        TagRepository.dismissSuggestion(context, "c174-none.ogg", "x")
        assertTrue(TranscriptDatabase.get(context).summaryDao().summary("c174-none.ogg") == null)
    }

    @Test
    fun a_tag_added_later_shows_in_the_flows_that_were_asked_for_earlier() = runBlocking {
        val name = "c164-live.ogg"
        TagRepository.add(context, name, "plumber")

        assertEquals(listOf("plumber"), TagRepository.tagsFor(context, name).first())
        assertEquals(setOf("plumber"), TagRepository.assignments(context).first()[name])
        assertTrue(TagRepository.allTags(context).first().any { it.tag == "plumber" })
        assertTrue(name in TagRepository.taggedWith(context, "plumber").first())
    }

    @Test
    fun a_turkish_spelling_of_an_existing_tag_reuses_it() = runBlocking {
        val name = "c148-turkish.ogg"
        TagRepository.add(context, name, "İnşaat")

        val stored = TagRepository.add(context, "c148-turkish-2.ogg", "inşaat")

        assertEquals("İnşaat", stored)
    }
}
