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
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the hub's badges say once there is something to count.
 *
 * The case worth pinning is the one that is easy to get wrong and impossible to see: a transcript
 * that is queued, running or failed is not something to read, so a card reading "3 transcripts" for
 * three failed attempts would be a lie the user can only discover by tapping it.
 *
 * The "never transcribed" case lives in its own class — see [LibraryCountsUntouchedDatabaseTest] —
 * because this one creates the database it is about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCountsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Robolectric hands classes with the same `@Config` one classloader, and `TranscriptDatabase`
     * holds its instance in a static — so rows written by a test in *another* class are still there
     * when this one asks for a count. Left alone, this class passes or fails according to the order
     * Gradle happens to scan test classes in, which is not a property of the code under test.
     */
    @Before
    fun emptyTheDatabase() = runBlocking {
        val db = TranscriptDatabase.get(context)
        db.transcriptDao().allTranscripts().forEach { db.transcriptDao().deleteFor(it.displayName) }
        db.summaryDao().observeAllDisplayNames().first().forEach { db.summaryDao().deleteFor(it) }
    }

    private fun transcript(name: String, state: TranscriptState, at: Long) = TranscriptEntry(
        displayName = name,
        state = state,
        updatedAt = at
    )

    private fun summary(name: String, at: Long) = CallSummaryEntry(
        displayName = name,
        document = """{"summary":"said things"}""",
        model = "test",
        createdAt = at
    )

    @Test
    fun only_a_finished_transcript_counts() = runBlocking {
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(transcript("done-a.ogg", TranscriptState.DONE, at = 200L))
        dao.upsertTranscript(transcript("done-b.ogg", TranscriptState.DONE, at = 100L))
        dao.upsertTranscript(transcript("queued.ogg", TranscriptState.QUEUED, at = 300L))
        dao.upsertTranscript(transcript("running.ogg", TranscriptState.RUNNING, at = 300L))
        dao.upsertTranscript(transcript("failed.ogg", TranscriptState.FAILED, at = 300L))

        assertEquals(2, LibraryCounts.transcribed(context).first())

        // The page is handed every state, newest change first — it has to be able to say that a
        // transcription is waiting or has failed, which the count deliberately never mentions.
        val page = LibraryCounts.transcripts(context).first()
        assertEquals(5, page.size)
        assertEquals(
            listOf("done-a.ogg", "done-b.ogg"),
            page.filter { it.state == TranscriptState.DONE }.map { it.displayName }
        )
        // Ordering is by updatedAt DESC, so the three 300L rows lead and the older pair follows.
        assertEquals(listOf("done-a.ogg", "done-b.ogg"), page.takeLast(2).map { it.displayName })
    }

    @Test
    fun every_summary_counts_and_the_newest_is_first() = runBlocking {
        val dao = TranscriptDatabase.get(context).summaryDao()
        dao.upsert(summary("older.ogg", at = 100L))
        dao.upsert(summary("newer.ogg", at = 900L))

        assertEquals(2, LibraryCounts.summarised(context).first())
        assertEquals(
            listOf("newer.ogg", "older.ogg"),
            LibraryCounts.summarisedNames(context).first()
        )
    }
}
