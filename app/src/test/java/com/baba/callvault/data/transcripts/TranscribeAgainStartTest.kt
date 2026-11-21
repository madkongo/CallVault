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
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What tapping "Transcribe again" does to the stored words, backup and summary (C95, C73). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscribeAgainStartTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
    }

    private suspend fun finished(name: String, vararg words: String) {
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(TranscriptEntry(name, TranscriptState.DONE, modelId = "m", language = "he", updatedAt = 5L))
        dao.replaceSegments(name, words.mapIndexed { i, w -> TranscriptSegmentEntry(displayName = name, startMs = i * 1000L, endMs = i * 1000L + 900, text = w, speaker = null) })
    }

    @Test
    fun `a second tap while the first is still queued keeps the old words and the backup`() = runBlocking {
        finished("twice.ogg", "old words")

        TranscribeAgain.start(context, "twice.ogg", null, null, deleteSummary = false)
        TranscribeAgain.start(context, "twice.ogg", null, null, deleteSummary = false)

        val dao = TranscriptDatabase.get(context).transcriptDao()
        assertEquals(listOf("old words"), dao.segmentsFor("twice.ogg").map { it.text })
        val kept = TranscribeAgainBackup.peek(context, "twice.ogg")
        assertNotNull("the backup of the finished transcript must survive a second tap", kept)
        assertEquals(TranscriptState.DONE, kept!!.state)
    }

    @Test
    fun `ticking delete summary does not delete it when the run is only queued`() = runBlocking {
        finished("keepsum.ogg", "old words")
        val summaries = TranscriptDatabase.get(context).summaryDao()
        summaries.upsert(CallSummaryEntry("keepsum.ogg", "{}", "m", createdAt = 1L, searchText = "old"))

        TranscribeAgain.start(context, "keepsum.ogg", null, null, deleteSummary = true)

        assertNotNull(summaries.summary("keepsum.ogg"))
    }

    @Test
    fun `a redo without a transcript row behind it still clears like a plain retry`() = runBlocking {
        TranscribeAgain.start(context, "fresh.ogg", null, null, deleteSummary = false)

        assertNull(TranscribeAgainBackup.peek(context, "fresh.ogg"))
        assertEquals(TranscriptState.QUEUED, TranscriptDatabase.get(context).transcriptDao().findTranscript("fresh.ogg")!!.state)
    }
}
