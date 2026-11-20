/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.db.MergePartEntry
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A foreign file the rescan or the first-run seed catalogued must still never be deleted by retention or the size
 * cap (review C10, second pass): being in the catalog shows it in the list, it does not make it CallVault's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetentionKeepsForeignCataloguedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(context)
    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val parts get() = RecordingDatabase.get(context).mergePartDao()
    private val old = 1_000L
    private val foreign = "audio_2024-03-05_14-22-10.ogg"
    private val ours = "20240101_101010.000+0200_in_0501111111.ogg"
    private val mergedForeignShape = "Recording_20240305_142210.m4a"

    private fun local(name: String) = "content://com.example.dev/tree/r/document/$name"

    @Before
    fun setUp(): Unit = runBlocking {
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        prefs.setRecordingFolderUri(null)
        prefs.setDriveFolderUri(null)
        prefs.setStorageTarget(StorageTarget.LOCAL)
        prefs.setFileNameTemplate(AppPreferences.DefaultsValue.FILE_NAME_TEMPLATE)
        prefs.setRetentionDriveDays(0)
        listOf(foreign, ours, mergedForeignShape).forEachIndexed { i, n ->
            dao.upsert(RecordingEntry(n, localUri = local(n), localSizeBytes = 500L, lastModified = old + i))
        }
        // A merge output is CallVault's whatever its name: the merge wrote it.
        parts.insertAll(listOf(MergePartEntry(mergedForeignShape, 1, "x.m4a", 0, 1, 0L, 1L, 1L, 0L, 0L)))
        mockkObject(RecordingsRepository)
        coEvery { RecordingsRepository.deleteCopy(any(), any(), any()) } returns true
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        parts.deleteParts(mergedForeignShape)
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        prefs.setRetentionLocalDays(0)
        prefs.setStorageCapBytes(0L)
    }

    private fun sweep() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<RetentionSweepWorker>(context).build().doWork())
    }

    private fun deleted(name: String) = runCatching {
        coVerify(atLeast = 1) { RecordingsRepository.deleteCopy(any(), Uri.parse(local(name)), any()) }
    }.isSuccess

    @Test
    fun `age retention never deletes a catalogued foreign file`() {
        prefs.setRetentionLocalDays(7)

        sweep()

        assertFalse(deleted(foreign))
        assertTrue(deleted(ours))
        assertTrue(deleted(mergedForeignShape))
    }

    @Test
    fun `the size cap never evicts a catalogued foreign file`() {
        prefs.setStorageCapBytes(100L)

        sweep()

        assertFalse(deleted(foreign))
        assertTrue(deleted(ours))
    }
}
