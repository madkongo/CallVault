/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.transcription.TranscriptionQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a recording row needs to know about its transcript, and the few actions it offers.
 *
 * The interesting cases are the boring-sounding ones: a recording that has never been transcribed is
 * the *common* case and must be a state the UI can render, not a null it has to special-case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class TranscriptRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() = runBlocking {
        // retry() enqueues work, so WorkManager has to exist or the call throws before it can clear
        // anything. Synchronous executor: nothing here waits on a worker actually running.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        )
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
    }

    @Test
    fun a_recording_with_no_transcript_reports_NONE() = runBlocking {
        val statuses = TranscriptRepository.statusesFor(context, listOf("never.ogg")).first()

        assertEquals(TranscriptStatus.NONE, statuses["never.ogg"])
    }

    @Test
    fun reports_an_entry_for_every_requested_name_even_when_the_table_is_empty() = runBlocking {
        // The UI indexes by name; a missing key would render nothing at all for that row.
        val names = listOf("a.ogg", "b.ogg", "c.ogg")

        val statuses = TranscriptRepository.statusesFor(context, names).first()

        assertEquals(names.toSet(), statuses.keys)
    }

    @Test
    fun a_library_larger_than_sqlites_variable_limit_still_gets_its_statuses() = runBlocking {
        // C52: the names were bound one by one into an IN list; platform SQLite 3.28 (Android 11) caps that at 999
        // and the query threw inside Home's collectAsState. 40,000 is over every SQLite build's cap.
        store("done.ogg", TranscriptState.DONE)
        val names = (1..40_000).map { "call-$it.ogg" } + "done.ogg"

        val statuses = TranscriptRepository.statusesFor(context, names).first()

        assertEquals(names.size, statuses.size)
        assertEquals(TranscriptStatus.DONE, statuses["done.ogg"])
        assertEquals(TranscriptStatus.NONE, statuses["call-7.ogg"])
    }

    @Test
    fun reports_the_stored_state_for_a_recording_that_has_one() = runBlocking {
        // Arrange
        store("done.ogg", TranscriptState.DONE)
        store("failed.ogg", TranscriptState.FAILED)
        store("running.ogg", TranscriptState.RUNNING)

        // Act
        val statuses = TranscriptRepository
            .statusesFor(context, listOf("done.ogg", "failed.ogg", "running.ogg")).first()

        // Assert
        assertEquals(TranscriptStatus.DONE, statuses["done.ogg"])
        assertEquals(TranscriptStatus.FAILED, statuses["failed.ogg"])
        assertEquals(TranscriptStatus.RUNNING, statuses["running.ogg"])
    }

    @Test
    fun retrying_a_failed_recording_makes_it_eligible_for_the_queue_again() = runBlocking {
        // The queue deliberately skips FAILED so a hopeless file is not retried nightly forever. That
        // is exactly why an explicit retry has to clear the state — otherwise the button does nothing
        // and the user has no way back.
        RecordingCatalog.recordLocal(context, "broken.ogg", "content://local/broken".toUri(), 10L, 1L)
        store("broken.ogg", TranscriptState.FAILED)
        assertTrue(TranscriptionQueue.pending(context).isEmpty())

        // Act
        TranscriptRepository.retry(context, "broken.ogg")

        // Assert
        assertEquals(listOf("broken.ogg"), TranscriptionQueue.pending(context))
    }

    @Test
    fun deleting_a_transcript_leaves_the_recording_alone() = runBlocking {
        // Someone may want the audio but not a searchable text of it.
        RecordingCatalog.recordLocal(context, "keep.ogg", "content://local/keep".toUri(), 10L, 1L)
        store("keep.ogg", TranscriptState.DONE)

        TranscriptRepository.delete(context, "keep.ogg")

        assertNull(TranscriptDatabase.get(context).transcriptDao().findTranscript("keep.ogg"))
        assertTrue(RecordingCatalog.all(context).any { it.displayName == "keep.ogg" })
    }

    @Test
    fun search_finds_a_recording_by_a_word_spoken_inside_it() = runBlocking {
        store("call.ogg", TranscriptState.DONE)
        segments("call.ogg", "נתראה בפגישה מחר")

        val hits = TranscriptRepository.search(context, "בפגישה")

        assertEquals(listOf("call.ogg"), hits.map { it.displayName })
    }

    @Test
    fun a_query_containing_fts_syntax_does_not_crash() = runBlocking {
        // MATCH takes an expression, not a literal: a stray quote or asterisk typed naturally is a
        // syntax error inside SQLite, which would surface as a crash rather than "no results".
        store("call.ogg", TranscriptState.DONE)
        segments("call.ogg", "hello there")

        listOf("\"", "*", "foo\"bar", "AND", "^", "(unbalanced").forEach { query ->
            TranscriptRepository.search(context, query) // must not throw
        }
    }

    @Test
    fun an_unmatched_query_returns_nothing_rather_than_everything() = runBlocking {
        store("call.ogg", TranscriptState.DONE)
        segments("call.ogg", "hello there")

        assertTrue(TranscriptRepository.search(context, "absent").isEmpty())
    }

    @Test
    fun a_blank_query_returns_nothing() = runBlocking {
        store("call.ogg", TranscriptState.DONE)
        segments("call.ogg", "hello there")

        assertTrue(TranscriptRepository.search(context, "   ").isEmpty())
    }

    private suspend fun store(name: String, state: TranscriptState) {
        TranscriptDatabase.get(context).transcriptDao()
            .upsertTranscript(TranscriptEntry(displayName = name, state = state))
    }

    private suspend fun segments(name: String, text: String) {
        TranscriptDatabase.get(context).transcriptDao().replaceSegments(
            name,
            listOf(TranscriptSegmentEntry(displayName = name, startMs = 0, endMs = 1000, text = text))
        )
    }
}
