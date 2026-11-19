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
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.db.RecordingDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Drive size the catalog keeps is the proof retention relies on (review C7, second pass). It must be the size
 * Drive itself reported, never the device file's size copied across: otherwise an upload Drive never confirmed
 * "proves" itself, and retention deletes the only copy. Runs the real after-call copy worker; only the copier
 * process is stubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveSizeStampTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20260101_101010.000+0200_in_0501111111.ogg"
    private val src = Uri.parse("content://com.example.dev/tree/r/document/$name")
    private val driveCopy = Uri.parse("content://com.google.android.apps.docs.storage/tree/d/document/$name")
    private val size = 500L
    private val dao get() = RecordingDatabase.get(context).recordingDao()

    @Before
    fun setUp(): Unit = runBlocking {
        RecordingCatalog.recordLocal(context, name, src, size, 1_000L)
        mockkObject(SafHelper)
        every { SafHelper.fileSize(any(), src) } returns size
        mockkConstructor(DriveCopyClient::class)
        justRun { anyConstructed<DriveCopyClient>().close() }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        dao.deleteByName(name)
    }

    private fun copyReturns(result: SafHelper.CopyResult, driveReported: Long) {
        coEvery { anyConstructed<DriveCopyClient>().call(any(), any(), any()) } returns
            DriveCopyClient.Outcome.Reply(DriveCopyProtocol.copyReply(result, CopyTimings(), driveReported))
    }

    private fun runCopy() = runBlocking {
        TestListenableWorkerBuilder<RecordingCopyWorker>(context)
            .setInputData(
                workDataOf(
                    RecordingCopyWorker.KEY_SRC to src.toString(),
                    RecordingCopyWorker.KEY_DEST_FOLDER to "content://com.google.android.apps.docs.storage/tree/d",
                    RecordingCopyWorker.KEY_NAME to name,
                    RecordingCopyWorker.KEY_DELETE_LOCAL to false,
                )
            ).build().doWork()
    }

    @Test
    fun `an upload Drive did not confirm records no Drive size, so retention keeps the device copy`() = runBlocking {
        copyReturns(SafHelper.CopyResult.Copied(driveCopy, size), driveReported = -1L)

        runCopy()

        val row = dao.findByName(name)!!
        assertEquals(driveCopy.toString(), row.driveUri)
        assertNull("the device size must not stand in for Drive's", row.driveSizeBytes)
        assertFalse(RetentionPolicy.deviceCopyMayGo(StorageTarget.BOTH, row))
    }

    @Test
    fun `a copy already in Drive with no confirmed size proves nothing either`() = runBlocking {
        copyReturns(SafHelper.CopyResult.AlreadyPresent(driveCopy), driveReported = -1L)

        runCopy()

        assertNull(dao.findByName(name)!!.driveSizeBytes)
    }

    @Test
    fun `a size Drive reported is recorded and lets the device copy age out`() = runBlocking {
        copyReturns(SafHelper.CopyResult.Copied(driveCopy, size), driveReported = size)

        runCopy()

        val row = dao.findByName(name)!!
        assertEquals(size, row.driveSizeBytes)
        assertTrue(RetentionPolicy.deviceCopyMayGo(StorageTarget.BOTH, row))
    }
}
