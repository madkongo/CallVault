/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** C112: deleting a summary or a transcript must not leave the deleted text in the `.md` beside the audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptRepositorySidecarTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folderUri: Uri = "content://tree/folder".toUri()
    private val written = ByteArrayOutputStream()
    private val oldSidecar = mockk<DocumentFile>(relaxed = true)
    private val folder = mockk<DocumentFile>(relaxed = true)

    @Before
    fun setUp() = runBlocking<Unit> {
        val db = TranscriptDatabase.get(context)
        db.transcriptDao().upsertTranscript(TranscriptEntry(CALL, TranscriptState.DONE, "m", "en", 0L, null))
        db.transcriptDao().replaceSegments(CALL, listOf(TranscriptSegmentEntry(displayName = CALL, startMs = 0L, endMs = 1000L, text = "hello there", speaker = null)))
        db.summaryDao().upsert(
            CallSummaryEntry(
                displayName = CALL,
                document = """{"intent":"Chasing invoice 4021","summary":"Something was said.","keyPoints":[],"decisions":[],"actionItems":[],"keyFacts":[]}""",
                searchText = "Chasing invoice 4021", model = "m", createdAt = 1L,
            ),
        )
        AppPreferences(context).setRecordingFolderUri(folderUri)
        AppPreferences(context).setWriteTranscriptSidecarEnabled(true)
        mockkStatic(DocumentFile::class)
        every { folder.findFile("call.md") } returns oldSidecar
        every { folder.createFile(any(), any()) } answers {
            val doc = mockk<DocumentFile>(relaxed = true)
            val uri = "content://tree/folder/${secondArg<String>()}".toUri()
            every { doc.uri } returns uri
            shadowOf(context.contentResolver).registerOutputStream(uri, written)
            doc
        }
        every { DocumentFile.fromTreeUri(any(), any()) } returns folder
        Unit
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        unmockkStatic(DocumentFile::class)
        AppPreferences(context).setWriteTranscriptSidecarEnabled(false)
        TranscriptDatabase.get(context).transcriptDao().deleteFor(CALL)
        TranscriptDatabase.get(context).summaryDao().deleteFor(CALL)
    }

    @Test
    fun `deleting the summary rewrites the sidecar without it`() = runBlocking<Unit> {
        TranscriptRepository.deleteSummary(context, CALL)

        verify { oldSidecar.delete() }
        val text = written.toString()
        assertTrue("the transcript stays", text.contains("hello there"))
        assertFalse("the deleted summary must be gone", text.contains("Chasing invoice 4021"))
    }

    @Test
    fun `deleting the transcript removes the sidecar when nothing remains`() = runBlocking<Unit> {
        TranscriptDatabase.get(context).summaryDao().deleteFor(CALL)

        TranscriptRepository.delete(context, CALL)

        verify { oldSidecar.delete() }
        verify(exactly = 0) { folder.createFile(any(), any()) }
    }

    @Test
    fun `deleting the summary does nothing to the folder when sidecars are off`() = runBlocking<Unit> {
        AppPreferences(context).setWriteTranscriptSidecarEnabled(false)

        TranscriptRepository.deleteSummary(context, CALL)

        verify(exactly = 0) { folder.createFile(any(), any()) }
        verify(exactly = 0) { oldSidecar.delete() }
    }

    private companion object {
        const val CALL = "call.ogg"
    }
}
