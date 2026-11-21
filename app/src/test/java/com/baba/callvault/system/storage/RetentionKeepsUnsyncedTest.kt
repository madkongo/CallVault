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
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.UntrackedRecordings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
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
 * Retention and the size cap in Both/Drive mode (review C7): a device copy may only be deleted once its Drive copy is
 * catalogued at the same size. Before that the device copy is the only one, and deleting it loses the call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetentionKeepsUnsyncedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(context)
    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val old = 1_000L // 1970: long past any retention period
    private val unsynced = "20240101_101010.000+0200_in_0501111111.ogg"
    private val synced = "20240101_111010.000+0200_in_0502222222.ogg"
    private val halfSynced = "20240101_121010.000+0200_in_0503333333.ogg"

    private fun local(name: String) = "content://com.example.dev/tree/r/document/$name"

    @Before
    fun setUp(): Unit = runBlocking {
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        // The one-time forget of pre-C7 Drive sizes is done; the rows below stand for sizes Drive confirmed.
        com.baba.callvault.data.recordings.RecordingCatalog.forgetUnconfirmedDriveSizesOnce(context)
        prefs.setRecordingFolderUri(null)
        prefs.setDriveFolderUri(null)
        prefs.setStorageTarget(StorageTarget.BOTH)
        prefs.setRetentionDriveDays(0)
        dao.upsert(RecordingEntry(unsynced, localUri = local(unsynced), localSizeBytes = 500L, lastModified = old))
        dao.upsert(
            RecordingEntry(
                synced, localUri = local(synced), driveUri = "content://drive/$synced",
                localSizeBytes = 500L, driveSizeBytes = 500L, lastModified = old + 1,
            )
        )
        // A Drive copy that stopped short: not proof that the call reached Drive.
        dao.upsert(
            RecordingEntry(
                halfSynced, localUri = local(halfSynced), driveUri = "content://drive/$halfSynced",
                localSizeBytes = 500L, driveSizeBytes = 120L, lastModified = old + 2,
            )
        )
        mockkObject(RecordingsRepository)
        coEvery { RecordingsRepository.deleteCopy(any(), any(), any()) } returns true
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
        prefs.setRetentionLocalDays(0)
        prefs.setStorageCapBytes(0L)
        prefs.setStorageTarget(StorageTarget.LOCAL)
    }

    private fun sweep() = runBlocking {
        val result = TestListenableWorkerBuilder<RetentionSweepWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    private fun deleted(name: String): Boolean = runCatching {
        coVerify(atLeast = 1) { RecordingsRepository.deleteCopy(any(), Uri.parse(local(name)), any()) }
    }.isSuccess

    @Test
    fun `age retention keeps a device copy that has not reached Drive`() {
        prefs.setRetentionLocalDays(7)
        prefs.setStorageCapBytes(0L)

        sweep()

        assertFalse("unsynced device copy must survive", deleted(unsynced))
        assertFalse("a short Drive copy is not proof", deleted(halfSynced))
        assertTrue("a device copy proven in Drive still ages out", deleted(synced))
    }

    @Test
    fun `the size cap keeps a device copy that has not reached Drive`() {
        prefs.setRetentionLocalDays(0)
        prefs.setStorageCapBytes(100L)

        sweep()

        assertFalse(deleted(unsynced))
        assertFalse(deleted(halfSynced))
        assertTrue(deleted(synced))
    }

    @Test
    fun `in device-only mode the age rule is unchanged`() {
        prefs.setStorageTarget(StorageTarget.LOCAL)
        prefs.setRetentionLocalDays(7)
        prefs.setStorageCapBytes(0L)

        sweep()

        assertTrue(deleted(unsynced))
    }

    // ---- the untracked pass (review C7, second pass)

    private val untrackedName = "20240101_131010.000+0200_in_0504444444.ogg"
    private val untrackedUri = Uri.parse(local(untrackedName))

    private fun withUntrackedDeviceFile(): DocumentFile {
        val item = RecordingItem(untrackedUri, untrackedName, 500L, old, null, null, old, number = null)
        mockkObject(UntrackedRecordings)
        coEvery { UntrackedRecordings.find(any()) } returns UntrackedRecordings.Found(device = listOf(item), drive = emptyList())
        val doc = mockk<DocumentFile>()
        every { doc.delete() } returns true
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(any(), untrackedUri) } returns doc
        return doc
    }

    @Test
    fun `in Both mode an expired untracked device file is kept, having no proven Drive copy`() {
        prefs.setRetentionLocalDays(7)
        prefs.setStorageCapBytes(0L)
        val doc = withUntrackedDeviceFile()

        sweep()

        verify(exactly = 0) { doc.delete() }
    }

    @Test
    fun `in device-only mode an expired untracked device file still ages out`() {
        prefs.setStorageTarget(StorageTarget.LOCAL)
        prefs.setRetentionLocalDays(7)
        prefs.setStorageCapBytes(0L)
        val doc = withUntrackedDeviceFile()

        sweep()

        verify(exactly = 1) { doc.delete() }
    }

    // ---- the one-time clear of pre-C7 Drive sizes has not succeeded yet

    @Test
    fun `until the old Drive sizes are cleared no device copy is deleted, by age or by the cap`() {
        mockkObject(com.baba.callvault.data.recordings.RecordingCatalog)
        coEvery { com.baba.callvault.data.recordings.RecordingCatalog.forgetUnconfirmedDriveSizesOnce(any()) } returns false
        prefs.setRetentionLocalDays(7)
        prefs.setStorageCapBytes(100L)

        sweep()

        assertFalse("not even a copy whose stored size matches", deleted(synced))
        assertFalse(deleted(unsynced))
        assertFalse(deleted(halfSynced))
    }
}
