/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.db.RecordingDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A delete whose caller goes away while the file is being deleted must still clean up its catalog row (C111). The
 * files are gone by then; leaving the row, transcript and notes behind is the half-cleaned state the bug produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteSurvivesCancellationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = RecordingDatabase.get(context).recordingDao()

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
    }

    private fun fileThatCancelsOnDelete(job: () -> Job): DocumentFile {
        val doc = mockk<DocumentFile>(relaxed = true)
        every { doc.exists() } returns true
        every { doc.delete() } answers { job().cancel(); true }
        return doc
    }

    @Test
    fun `deleteFile drops the row even when the caller is cancelled during the file delete`(): Unit = runBlocking {
        val uri = "content://com.example.dev/tree/r/document/c111.ogg".toUri()
        RecordingCatalog.recordLocal(context, "c111.ogg", uri, 10L, 0L)
        lateinit var job: Job
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(any(), any()) } returns fileThatCancelsOnDelete { job }
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { RecordingsRepository.deleteFile(context, uri) }
        job.start()
        job.join()

        assertNull("the row of a file that is gone was left in the catalog", dao.findByName("c111.ogg"))
    }
}
