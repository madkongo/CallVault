/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.interop

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * The sidecar backfill lists the recording folder once, however many recordings it writes for
 * (BabApps item 6). [DocumentFile.findFile] lists the whole folder on each call, about 18 s each on a
 * phone with 3,000 files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSidecarFolderListingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folderUri: Uri = "content://tree/folder".toUri()
    private val names = (1..5).map { "call_$it.ogg" }

    @Before
    fun setUp() = runBlocking {
        names.forEach {
            RecordingCatalog.recordLocal(context, it, "content://local/$it".toUri(), 10L, 1L)
            RecordingExtrasRepository.saveNote(context, it, "note for $it")
        }
        AppPreferences(context).setRecordingFolderUri(folderUri)
        AppPreferences(context).setWriteTranscriptSidecarEnabled(true)
        mockkStatic(DocumentFile::class)
    }

    @After
    fun tearDown() = runBlocking {
        unmockkStatic(DocumentFile::class)
        AppPreferences(context).setWriteTranscriptSidecarEnabled(false)
        names.forEach { RecordingCatalog.removeName(context, it) }
    }

    @Test
    fun `backfill lists the folder once and never calls findFile`() = runBlocking {
        val existing = mockk<DocumentFile>(relaxed = true)
        every { existing.name } returns "call_1.md"
        val folder = mockk<DocumentFile>(relaxed = true)
        every { folder.listFiles() } returns arrayOf(existing)
        every { folder.findFile(any()) } throws AssertionError("findFile lists the whole folder")
        every { folder.createFile(any(), any()) } answers {
            val name = secondArg<String>()
            val doc = mockk<DocumentFile>(relaxed = true)
            val uri = "content://tree/folder/$name".toUri()
            every { doc.uri } returns uri
            shadowOf(context.contentResolver).registerOutputStream(uri, ByteArrayOutputStream())
            doc
        }
        every { DocumentFile.fromTreeUri(any(), any()) } returns folder

        TranscriptSidecar.backfillAll(context)

        verify(exactly = 1) { folder.listFiles() }
        verify(exactly = 0) { folder.findFile(any()) }
        // The stale call_1.md was replaced; the others were created: one write per recording.
        verify(exactly = 1) { existing.delete() }
        verify(exactly = names.size) { folder.createFile(any(), any()) }
    }

    @Test
    fun `the index finds, replaces and forgets files by name`() {
        val a = mockk<DocumentFile>()
        every { a.name } returns "a.md"
        val index = TranscriptSidecar.FolderIndex(mockk(), listOf(a))
        assertTrue(index.contains("a.md"))
        assertEquals(a, index.remove("a.md"))
        assertEquals(null, index.remove("a.md"))
    }
}
