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
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.db.RecordingDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Drive sweep's two stamping paths (review C7): the size kept on the row is the one Drive listed or reported,
 * never the device file's. Runs the real sweep; the device folder and the copier process are stubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSweepDriveSizeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val name = "20260101_101010.000+0200_in_0501111111.ogg"
    private val deviceFolder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARec")
    private val driveFolder = Uri.parse("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dd")
    private val src = Uri.parse("$deviceFolder/document/primary%3ARec%2F$name")
    private val driveCopy = Uri.parse("$driveFolder/document/acc%3D1%3Bdoc%3Dx")
    private val size = 500L

    @Before
    fun setUp(): Unit = runBlocking {
        val prefs = AppPreferences(context)
        prefs.setStorageTarget(StorageTarget.BOTH)
        prefs.setRecordingFolderUri(deviceFolder)
        prefs.setDriveFolderUri(driveFolder)
        RecordingCatalog.recordLocal(context, name, src, size, 1_000L)

        val file = mockk<DocumentFile>(relaxed = true)
        every { file.name } returns name
        every { file.isFile } returns true
        every { file.length() } returns size
        every { file.uri } returns src
        every { file.type } returns "audio/ogg"
        every { file.lastModified() } returns 1_000L
        val dir = mockk<DocumentFile>(relaxed = true)
        every { dir.exists() } returns true
        every { dir.listFiles() } returns arrayOf(file)
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(any(), deviceFolder) } returns dir

        mockkConstructor(DriveCopyClient::class)
        justRun { anyConstructed<DriveCopyClient>().close() }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        dao.deleteByName(name)
        AppPreferences(context).setStorageTarget(StorageTarget.LOCAL)
    }

    private fun driveLists(sizeListed: Long?) {
        val listing = if (sizeListed == null) DriveCopyProtocol.Listing(emptyList(), emptyList(), emptyList())
        else DriveCopyProtocol.Listing(listOf(name), listOf(driveCopy), listOf(sizeListed))
        coEvery { anyConstructed<DriveCopyClient>().call(DriveCopyProtocol.MSG_LIST, any(), any()) } returns
            DriveCopyClient.Outcome.Reply(DriveCopyProtocol.listReply(listing))
    }

    private fun copyReports(driveReported: Long) {
        coEvery { anyConstructed<DriveCopyClient>().call(DriveCopyProtocol.MSG_COPY, any(), any()) } returns
            DriveCopyClient.Outcome.Reply(
                DriveCopyProtocol.copyReply(SafHelper.CopyResult.Copied(driveCopy, size), CopyTimings(), driveReported)
            )
    }

    private fun sweep() = runBlocking { TestListenableWorkerBuilder<SyncSweepWorker>(context).build().doWork() }

    @Test
    fun `a copy Drive did not confirm is stamped without a size`() = runBlocking {
        driveLists(sizeListed = null)
        copyReports(driveReported = -1L)

        sweep()

        assertEquals(driveCopy.toString(), dao.findByName(name)?.driveUri)
        assertNull(dao.findByName(name)?.driveSizeBytes)
    }

    @Test
    fun `a copy Drive reported is stamped with Drive's size`() = runBlocking {
        driveLists(sizeListed = null)
        copyReports(driveReported = size)

        sweep()

        assertEquals(size, dao.findByName(name)?.driveSizeBytes)
    }

    @Test
    fun `a copy already listed in Drive at the device size is stamped with the listed size`() = runBlocking {
        driveLists(sizeListed = size)

        sweep()

        assertEquals(driveCopy.toString(), dao.findByName(name)?.driveUri)
        assertEquals(size, dao.findByName(name)?.driveSizeBytes)
    }
}
