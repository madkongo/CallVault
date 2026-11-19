/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.system.storage.RetentionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rows stamped before review C7 carry the DEVICE size as their Drive size, which retention would take as proof the
 * call reached Drive. A one-time pass forgets every stored Drive size, so only a later Drive listing re-confirms it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnconfirmedDriveSizesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val a = "20240101_101010.000+0200_in_0501111111.ogg"
    private val b = "20240101_111010.000+0200_in_0502222222.ogg"

    @Before
    fun setUp(): Unit = runBlocking {
        dao.upsert(RecordingEntry(a, localUri = "content://dev/a", driveUri = "content://drive/a", localSizeBytes = 500L, driveSizeBytes = 500L))
        dao.upsert(RecordingEntry(b, driveUri = "content://drive/b", driveSizeBytes = 700L))
    }

    @After
    fun tearDown(): Unit = runBlocking { listOf(a, b).forEach { dao.deleteByName(it) } }

    @Test
    fun `the first run forgets every stored Drive size, so an old stamp proves nothing`() = runBlocking {
        assertTrue(RecordingCatalog.forgetUnconfirmedDriveSizesOnce(context))

        assertNull(dao.findByName(a)?.driveSizeBytes)
        assertNull(dao.findByName(b)?.driveSizeBytes)
        assertFalse(RetentionPolicy.deviceCopyMayGo(StorageTarget.BOTH, dao.findByName(a)!!))
    }

    @Test
    fun `it runs once, leaving sizes Drive confirmed afterwards alone`() = runBlocking {
        RecordingCatalog.forgetUnconfirmedDriveSizesOnce(context)
        dao.setDrive(a, "content://drive/a", 500L) // re-confirmed by a Drive listing

        assertTrue("already done counts as done", RecordingCatalog.forgetUnconfirmedDriveSizesOnce(context))
        assertEquals(500L, dao.findByName(a)?.driveSizeBytes)
    }

    @Test
    fun `a clear that fails reports failure and is tried again next time`() = runBlocking {
        val failed = RecordingCatalog.forgetUnconfirmedDriveSizesOnceWith(context) { error("database is locked") }

        assertFalse(failed)
        assertEquals(500L, dao.findByName(a)?.driveSizeBytes)
        assertTrue("a later run still clears", RecordingCatalog.forgetUnconfirmedDriveSizesOnce(context))
        assertNull(dao.findByName(a)?.driveSizeBytes)
    }

    @Test
    fun `a cancelled clear is not swallowed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { RecordingCatalog.forgetUnconfirmedDriveSizesOnceWith(context) { throw CancellationException("stopped") } }
        }
    }
}
