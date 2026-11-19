/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.db.MergePartEntry
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.StorageRouter
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
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
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Un-merge (review C6): the merged recording may only go once every part is published and read back. A part that
 * cannot be restored must leave the merged call, its manifest and its metadata exactly as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnMergeSafetyTest {

    private lateinit var context: Context
    private val merged = "20260901_143200.001+0300_out_5551234.m4a"
    private val partA = "20260901_143200.000+0300_out_5551234.m4a"
    private val partB = "20260901_144000.000+0300_in_5551234.m4a"
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ARec"
    private val uriA = Uri.parse("$folder/document/primary%3ARec%2Fa.m4a")
    private val uriB = Uri.parse("$folder/document/primary%3ARec%2Fb.m4a")
    private val cutBytes = 40

    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val parts get() = RecordingDatabase.get(context).mergePartDao()

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        AppPreferences(context).setRecordingFolderUri(Uri.parse(folder))
        val mergedFile = File(context.filesDir, merged).apply { writeBytes(ByteArray(80)) }
        RecordingCatalog.recordLocal(context, merged, Uri.fromFile(mergedFile), 80L, 1_000L)
        parts.insertAll(
            listOf(
                MergePartEntry(merged, 1, partA, 0, 10, 0L, 1_000_000L, 1L, 0L, 0L),
                MergePartEntry(merged, 2, partB, 10, 10, 1_000_000L, 1_000_000L, 2L, 0L, 0L),
            )
        )

        mockkObject(SafHelper, AudioSplit, MergeFormat, StorageRouter)
        every { AudioSplit.split(any(), any(), any(), any()) } answers {
            thirdArg<List<FileDescriptor>>().forEach { FileOutputStream(it).write(ByteArray(cutBytes)) }
        }
        every { MergeFormat.frameCount(any()) } returns 10
        every { SafHelper.deleteDocument(any(), any()) } returns true
        every { SafHelper.fileSize(any(), any()) } returns cutBytes.toLong()
        justRun { StorageRouter.route(any(), any(), any(), any()) }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        parts.deleteParts(merged)
        listOf(merged, partA, partB).forEach { dao.deleteByName(it) }
    }

    @Test
    fun `a part that cannot be published keeps the merged call and its manifest`() = runBlocking {
        every { SafHelper.publishStagedRecording(any(), any(), partA, any(), any()) } returns uriA
        every { SafHelper.publishStagedRecording(any(), any(), partB, any(), any()) } returns null

        val outcome = MergeService.unMerge(context, merged)

        assertTrue("a lost part is a failure, not a success: $outcome", outcome is MergeService.Outcome.Failed)
        assertNotNull("the merged call must still be catalogued", dao.findByName(merged)?.localUri)
        assertEquals(2, parts.partsOf(merged).size)
        assertNull("a half-restored part must not be catalogued", dao.findByName(partA))
        // Only the part that did publish is taken back out; the merged recording is never deleted.
        verify(exactly = 1) { SafHelper.deleteDocument(any(), partA) }
        verify(exactly = 0) { SafHelper.deleteDocument(any(), merged) }
    }

    @Test
    fun `a part that reads back short keeps the merged call`() = runBlocking {
        every { SafHelper.publishStagedRecording(any(), any(), partA, any(), any()) } returns uriA
        every { SafHelper.publishStagedRecording(any(), any(), partB, any(), any()) } returns uriB
        every { SafHelper.fileSize(any(), uriB) } returns 3L

        val outcome = MergeService.unMerge(context, merged)

        assertTrue(outcome is MergeService.Outcome.Failed)
        assertNotNull(dao.findByName(merged)?.localUri)
        assertEquals(2, parts.partsOf(merged).size)
        verify(exactly = 0) { SafHelper.deleteDocument(any(), merged) }
    }

    @Test
    fun `the merged call goes only after every part is published, staged outside the cache`() = runBlocking {
        val staged = mutableListOf<File>()
        every { SafHelper.publishStagedRecording(any(), any(), partA, any(), capture(staged)) } returns uriA
        every { SafHelper.publishStagedRecording(any(), any(), partB, any(), capture(staged)) } returns uriB

        val outcome = MergeService.unMerge(context, merged)

        assertEquals(MergeService.Outcome.UnMerged(2), outcome)
        assertNull(dao.findByName(merged))
        assertEquals(uriA.toString(), dao.findByName(partA)?.localUri)
        assertEquals(uriB.toString(), dao.findByName(partB)?.localUri)
        assertTrue(parts.partsOf(merged).isEmpty())
        verifyOrder {
            SafHelper.publishStagedRecording(any(), any(), partA, any(), any())
            SafHelper.publishStagedRecording(any(), any(), partB, any(), any())
            SafHelper.deleteDocument(any(), merged)
        }
        // Android may clear cacheDir under storage pressure, which would take the only copy of a part with it.
        staged.forEach { assertTrue(it.path, it.path.startsWith(context.filesDir.path)) }
    }
}
