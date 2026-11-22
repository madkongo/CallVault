/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingCatalog
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Review N6, through the real sweep: the folder lists but copies fail. The notice needs a whole spell with no proof
 * that Drive works (a straggler beside files Drive holds is not one), and a later listing must not clear it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSweepCopySpellTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private val good = "20260101_101010.000+0200_in_0501111111.ogg"
    private val bad = "20260101_111111.000+0200_in_0502222222.ogg"
    private val deviceFolder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ARec")
    private val driveFolder = Uri.parse("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dd")
    private val driveCopy = Uri.parse("$driveFolder/document/acc%3D1%3Bdoc%3Dx")
    private val size = 500L

    private fun doc(name: String): DocumentFile {
        val f = mockk<DocumentFile>(relaxed = true)
        every { f.name } returns name
        every { f.isFile } returns true
        every { f.length() } returns size
        every { f.uri } returns Uri.parse("$deviceFolder/document/primary%3ARec%2F$name")
        every { f.type } returns "audio/ogg"
        every { f.lastModified() } returns 1_000L
        return f
    }

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DriveBackupNotice.onDriveSyncOff(context, "test reset")
        DriveBackupNotice.onDriveReached(context, "test reset")
        nm.cancelAll()
        AppPreferences(context).apply {
            setStorageTarget(StorageTarget.BOTH)
            setRecordingFolderUri(deviceFolder)
            setDriveFolderUri(driveFolder)
        }
        val dir = mockk<DocumentFile>(relaxed = true)
        every { dir.exists() } returns true
        every { dir.listFiles() } returns arrayOf(doc(good), doc(bad))
        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(any(), deviceFolder) } returns dir
        mockkConstructor(DriveCopyClient::class)
        justRun { anyConstructed<DriveCopyClient>().close() }
        runBlocking {
            RecordingCatalog.recordLocal(context, good, Uri.parse("$deviceFolder/document/g"), size, 1_000L)
            RecordingCatalog.recordLocal(context, bad, Uri.parse("$deviceFolder/document/b"), size, 1_000L)
        }
    }

    @After
    fun tearDown() = unmockkAll()

    private fun driveLists(names: List<String>) {
        val listing = DriveCopyProtocol.Listing(names, names.map { driveCopy }, names.map { size })
        coEvery { anyConstructed<DriveCopyClient>().call(DriveCopyProtocol.MSG_LIST, any(), any()) } returns
            DriveCopyClient.Outcome.Reply(DriveCopyProtocol.listReply(listing))
    }

    private fun copiesFail() {
        coEvery { anyConstructed<DriveCopyClient>().call(DriveCopyProtocol.MSG_COPY, any(), any()) } returns
            DriveCopyClient.Outcome.TimedOut
    }

    private fun sweep(attempt: Int) = runBlocking {
        TestListenableWorkerBuilder<SyncSweepWorker>(context).setRunAttemptCount(attempt).build().doWork()
    }

    @Test
    fun `one straggler that never copies, beside a recording Drive holds, never raises the notice`() {
        driveLists(listOf(good))
        copiesFail()
        repeat(CloudCopyPolicy.MAX_ATTEMPTS + 2) { sweep(minOf(it, CloudCopyPolicy.MAX_ATTEMPTS - 1)) }
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `every copy failing through a whole spell raises it once, and a later listing does not clear it`() {
        driveLists(emptyList())
        copiesFail()
        repeat(CloudCopyPolicy.MAX_ATTEMPTS) { sweep(it) }
        assertEquals(1, shadowOf(nm).allNotifications.size)

        sweep(0) // listing works again, copies still fail
        assertEquals("no flicker", 1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun `a spell shorter than the attempts is not yet told`() {
        driveLists(emptyList())
        copiesFail()
        repeat(CloudCopyPolicy.MAX_ATTEMPTS - 1) { sweep(it) }
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
