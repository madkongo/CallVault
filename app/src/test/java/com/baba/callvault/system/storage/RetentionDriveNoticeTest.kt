/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.system.health.SilentFailureNotifier
import io.mockk.coEvery
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The notice that retention is keeping recordings because Drive has not backed them up (owner decision N3): when
 * it shows, with what count, and that it clears once nothing is kept for that reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetentionDriveNoticeTest {

    // ---- the decision

    @Test
    fun `recordings kept for Drive post the notice with their count`() {
        assertEquals(RetentionDriveNotice.Action.Post(3), RetentionDriveNotice.decide(keptForDrive = 3))
    }

    @Test
    fun `nothing kept for Drive clears the notice`() {
        assertEquals(RetentionDriveNotice.Action.Clear, RetentionDriveNotice.decide(keptForDrive = 0))
    }

    @Test
    fun `the cap counts only what it would have evicted but for Drive`() {
        val candidates = listOf(
            StorageCapPolicy.Candidate("old-unsynced", 400L, 1L, isFavourite = false, isOnlyCopy = true),
            StorageCapPolicy.Candidate("new-unsynced", 400L, 9L, isFavourite = false, isOnlyCopy = true),
        )
        // 800 bytes over a 500-byte cap: only the oldest needed to go, so only it is "kept for Drive".
        assertEquals(setOf("old-unsynced"), RetentionDriveNotice.keptByCap(candidates, capBytes = 500L))
    }

    // ---- the sweep drives it

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(context)
    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val unsynced = "20240101_101010.000+0200_in_0501111111.ogg"

    @Before
    fun setUp(): Unit = runBlocking {
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        prefs.setRecordingFolderUri(null)
        prefs.setDriveFolderUri(null)
        prefs.setRetentionDriveDays(0)
        prefs.setStorageCapBytes(0L)
        prefs.setRetentionLocalDays(7)
        mockkObject(RecordingsRepository, SilentFailureNotifier)
        coEvery { RecordingsRepository.deleteFile(any(), any()) } returns true
        justRun { SilentFailureNotifier.updateRetentionWaitingForDrive(any(), any()) }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        prefs.setRetentionLocalDays(0)
        prefs.setStorageTarget(StorageTarget.LOCAL)
    }

    private fun sweep() = runBlocking { TestListenableWorkerBuilder<RetentionSweepWorker>(context).build().doWork() }

    @Test
    fun `a sweep that keeps an unsynced recording reports it`() = runBlocking {
        prefs.setStorageTarget(StorageTarget.BOTH)
        dao.upsert(RecordingEntry(unsynced, localUri = "content://dev/$unsynced", localSizeBytes = 500L, lastModified = 1_000L))

        sweep()

        verify { SilentFailureNotifier.updateRetentionWaitingForDrive(any(), 1) }
    }

    @Test
    fun `a sweep that keeps nothing for Drive clears it`() = runBlocking {
        prefs.setStorageTarget(StorageTarget.LOCAL)
        dao.upsert(RecordingEntry(unsynced, localUri = "content://dev/$unsynced", localSizeBytes = 500L, lastModified = 1_000L))

        sweep()

        verify { SilentFailureNotifier.updateRetentionWaitingForDrive(any(), 0) }
    }
}
