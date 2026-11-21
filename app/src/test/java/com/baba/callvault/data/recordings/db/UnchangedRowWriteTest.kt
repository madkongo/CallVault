/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R50: a Drive sweep stamping a row that already says the same must not write. Every write wakes Home's catalog
 * watcher, which re-lists the whole library, so unchanged rows cost minutes of CPU per daily sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnchangedRowWriteTest {

    private lateinit var db: RecordingDatabase
    private val name = "20260902_120205.887+0300_in_Dan.m4a"
    private val dao get() = db.recordingDao()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RecordingDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun stamping_the_same_drive_copy_again_writes_nothing() = runBlocking {
        dao.upsert(RecordingEntry(name, localUri = "content://local/a", lastModified = 1L))
        assertEquals(1, dao.setDrive(name, "content://drive/a", 4096L))

        assertEquals(0, dao.setDrive(name, "content://drive/a", 4096L))
    }

    @Test
    fun a_changed_size_or_address_is_written() = runBlocking {
        dao.upsert(RecordingEntry(name, driveUri = "content://drive/a", driveSizeBytes = 4096L, lastModified = 1L))

        assertEquals(1, dao.setDrive(name, "content://drive/a", 8192L))
        assertEquals(1, dao.setDrive(name, "content://drive/b", 8192L))
        assertEquals(8192L, dao.findByName(name)?.driveSizeBytes)
        assertEquals("content://drive/b", dao.findByName(name)?.driveUri)
    }

    @Test
    fun a_missing_size_is_compared_as_a_value_not_skipped() = runBlocking {
        dao.upsert(RecordingEntry(name, driveUri = "content://drive/a", driveSizeBytes = 4096L, lastModified = 1L))

        // Drive no longer confirms a size: the row must lose it, not keep the old proof.
        assertEquals(1, dao.setDrive(name, "content://drive/a", null))
        assertEquals(null, dao.findByName(name)?.driveSizeBytes)
        assertEquals(0, dao.setDrive(name, "content://drive/a", null))
    }

    @Test
    fun clearing_a_local_copy_that_is_already_gone_writes_nothing() = runBlocking {
        dao.upsert(RecordingEntry(name, driveUri = "content://drive/a", lastModified = 1L))

        assertEquals(0, dao.clearLocal(name))
    }

    @Test
    fun clearing_a_recorded_local_copy_still_works() = runBlocking {
        dao.upsert(RecordingEntry(name, localUri = "content://local/a", localSizeBytes = 10L, driveUri = "content://drive/a", lastModified = 1L))

        assertEquals(1, dao.clearLocal(name))
        assertEquals(null, dao.findByName(name)?.localUri)
    }
}
