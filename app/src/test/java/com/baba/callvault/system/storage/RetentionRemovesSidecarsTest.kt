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
import com.baba.callvault.data.recordings.RecordingSidecars
import com.baba.callvault.data.recordings.RecordingsRepository
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
 * A retention delete takes the sidecars (.md, .json) of every recording whose last copy it deleted, in one batch at the
 * end of the run, and never those of a recording that still has a copy (C41).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetentionRemovesSidecarsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(context)
    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val gone = "20240101_101010.000+0200_in_0501111111.ogg"
    private val kept = "20240102_101010.000+0200_in_0502222222.ogg"

    private fun local(name: String) = "content://com.example.dev/tree/r/document/$name"

    @Before
    fun setUp(): Unit = runBlocking {
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        prefs.setRecordingFolderUri(null)
        prefs.setDriveFolderUri(null)
        prefs.setStorageTarget(StorageTarget.LOCAL)
        prefs.setFileNameTemplate(AppPreferences.DefaultsValue.FILE_NAME_TEMPLATE)
        prefs.setRetentionDriveDays(0)
        prefs.setRetentionLocalDays(7)
        listOf(gone, kept).forEach { n ->
            dao.upsert(RecordingEntry(n, localUri = local(n), localSizeBytes = 500L, lastModified = 1_000L))
        }
        mockkObject(RecordingsRepository)
        mockkObject(RecordingSidecars)
        // Only the first recording's delete reports that its last copy went.
        coEvery { RecordingsRepository.deleteCopy(any(), any(), any()) } answers {
            val uri = secondArg<Uri>()
            if (uri == Uri.parse(local(gone))) thirdArg<MutableCollection<String>>() += gone
            true
        }
        coEvery { RecordingSidecars.deleteOrphanedFor(any(), any()) } returns 0
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        prefs.setRetentionLocalDays(0)
    }

    @Test
    fun `the sweep deletes the sidecars of a recording whose last copy it deleted, once, for the batch`() = runBlocking {
        TestListenableWorkerBuilder<RetentionSweepWorker>(context).build().doWork()

        coVerify(exactly = 1) { RecordingSidecars.deleteOrphanedFor(any(), match { it.toList() == listOf(gone) }) }
    }
}
