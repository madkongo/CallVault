/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * C112: with "save transcript and notes beside the recording" on, storing a summary must rewrite the
 * `.md` file, which until then carried a transcript but no summary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SummaryRunnerSidecarTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folderUri: Uri = "content://tree/folder".toUri()

    @Before
    fun setUp() = runBlocking {
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(TranscriptEntry(CALL, TranscriptState.DONE, "m", "en", 0L, null))
        dao.replaceSegments(CALL, listOf(TranscriptSegmentEntry(displayName = CALL, startMs = 0L, endMs = 1000L, text = "hello there", speaker = null)))
        AppPreferences(context).setRecordingFolderUri(folderUri)
        AppPreferences(context).setWriteTranscriptSidecarEnabled(true)
        mockkStatic(DocumentFile::class)
    }

    @After
    fun tearDown() = runBlocking {
        unmockkStatic(DocumentFile::class)
        AppPreferences(context).setWriteTranscriptSidecarEnabled(false)
        TranscriptDatabase.get(context).transcriptDao().deleteFor(CALL)
        TranscriptDatabase.get(context).summaryDao().deleteFor(CALL)
    }

    @Test
    fun `storing a summary rewrites the transcript sidecar so it carries the summary`() = runBlocking {
        val written = ByteArrayOutputStream()
        val folder = mockk<DocumentFile>(relaxed = true)
        every { folder.findFile(any()) } returns null
        every { folder.createFile(any(), any()) } answers {
            val doc = mockk<DocumentFile>(relaxed = true)
            val uri = "content://tree/folder/${secondArg<String>()}".toUri()
            every { doc.uri } returns uri
            shadowOf(context.contentResolver).registerOutputStream(uri, written)
            doc
        }
        every { DocumentFile.fromTreeUri(any(), any()) } returns folder
        val host = object : SummaryModelHost {
            override suspend fun run(modelPath: String, block: suspend (SummarySession) -> CallSummary?): CallSummary? =
                block { _, _, _ ->
                    """{"intent":"Chasing invoice 4021","summary":"Something was said.","keyPoints":[],"decisions":[],"actionItems":[],"keyFacts":[]}"""
                }
        }

        SummaryRunner(context, host, wasAborted = { false }).run(CALL, "m", "/p", "en", now = 1L)

        verify(exactly = 1) { folder.createFile(any(), "call.md") }
        assertTrue("the sidecar must carry the summary", written.toString().contains("Chasing invoice 4021"))
    }

    private companion object {
        const val CALL = "call.ogg"
    }
}
