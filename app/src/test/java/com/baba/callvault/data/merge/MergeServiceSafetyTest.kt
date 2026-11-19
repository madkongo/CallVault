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
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
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
import org.junit.Assert.assertFalse
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
 * The order merge removes things in (review C4): the originals may only go once the merged file is published in
 * the user's folder and read back at the right size. The audio layer is stubbed; it has its own instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergeServiceSafetyTest {

    private lateinit var context: Context
    private lateinit var staging: File
    private val partA = "20260901_143200.000+0300_out_5551234.m4a"
    private val partB = "20260901_144000.000+0300_in_5551234.m4a"
    private val publishedUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARec/document/primary%3ARec%2Fmerged.m4a")
    private val stagedBytes = 100

    private val dao get() = RecordingDatabase.get(context).recordingDao()

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val prefs = AppPreferences(context)
        prefs.setRecordingFolderUri(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARec"))
        prefs.setKeepOriginalsAfterMerge(false)
        listOf(partA, partB).forEach { name ->
            val file = File(context.filesDir, name).apply { writeBytes(ByteArray(50)) }
            RecordingCatalog.recordLocal(context, name, Uri.fromFile(file), 50L, 1_000L)
        }
        staging = File(context.filesDir, "merge-staging.tmp").apply { delete() }

        mockkObject(SafHelper, AudioConcat, MergeFormat, StorageRouter)
        every { SafHelper.createAudioFile(any(), any(), any(), any(), any()) } answers {
            staging.createNewFile()
            val fd = ParcelFileDescriptor.open(staging, ParcelFileDescriptor.MODE_READ_WRITE)
            SafHelper.SafResult(uri = null, descriptor = fd, displayName = "Rec/x", stagingFile = staging)
        }
        every { AudioConcat.concat(any(), any(), any()) } answers {
            FileOutputStream(secondArg<FileDescriptor>()).write(ByteArray(stagedBytes))
            listOf(
                AudioConcat.PartBoundary(0, 10, 0L, 1_000_000L, 0L, 0L),
                AudioConcat.PartBoundary(10, 5, 1_000_000L, 500_000L, 0L, 0L),
            )
        }
        every { MergeFormat.frameCount(any()) } returns 15
        every { SafHelper.deleteDocument(any(), any()) } returns true
        justRun { StorageRouter.route(any(), any(), any(), any()) }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        val parts = RecordingDatabase.get(context).mergePartDao()
        dao.getAll().forEach {
            parts.deleteParts(it.displayName)
            dao.deleteByName(it.displayName)
        }
    }

    @Test
    fun `a failed publish keeps both originals and leaves no staged copy behind`() = runBlocking {
        every { SafHelper.publishStagedRecording(any(), any(), any(), any(), any()) } returns null

        val outcome = MergeService.merge(context, partA, listOf(partB))

        assertTrue(outcome is MergeService.Outcome.Failed)
        assertNotNull("part A must still be catalogued", dao.findByName(partA)?.localUri)
        assertNotNull("part B must still be catalogued", dao.findByName(partB)?.localUri)
        verify(exactly = 0) { SafHelper.deleteDocument(any(), any()) }
        assertFalse("the staged merge must not be stranded", staging.exists())
    }

    @Test
    fun `a published file of the wrong size keeps both originals`() = runBlocking {
        every { SafHelper.publishStagedRecording(any(), any(), any(), any(), any()) } returns publishedUri
        every { SafHelper.fileSize(any(), publishedUri) } returns 7L

        val outcome = MergeService.merge(context, partA, listOf(partB))

        assertTrue(outcome is MergeService.Outcome.Failed)
        assertNotNull(dao.findByName(partA)?.localUri)
        assertNotNull(dao.findByName(partB)?.localUri)
        // Only the short published copy is removed, never an original.
        verify(exactly = 1) { SafHelper.deleteDocument(any(), any()) }
        assertNull("no merged row for a copy that was not proven", dao.getAll().firstOrNull { it.displayName != partA && it.displayName != partB })
    }

    @Test
    fun `originals are removed only after the merged file is published and read back`() = runBlocking {
        every { SafHelper.publishStagedRecording(any(), any(), any(), any(), any()) } returns publishedUri
        every { SafHelper.fileSize(any(), publishedUri) } returns stagedBytes.toLong()

        val outcome = MergeService.merge(context, partA, listOf(partB))

        assertTrue(outcome is MergeService.Outcome.Merged)
        val mergedName = (outcome as MergeService.Outcome.Merged).mergedName
        assertEquals(publishedUri.toString(), dao.findByName(mergedName)?.localUri)
        assertNull(dao.findByName(partA))
        assertNull(dao.findByName(partB))
        verifyOrder {
            SafHelper.publishStagedRecording(any(), any(), any(), any(), any())
            SafHelper.fileSize(any(), publishedUri)
            SafHelper.deleteDocument(any(), partA)
            SafHelper.deleteDocument(any(), partB)
        }
    }
}
