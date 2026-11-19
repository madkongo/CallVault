/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import io.mockk.every
import io.mockk.mockkObject
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
 * Drive reference repair after the Drive folder changed (review C8). A Drive-only recording whose reference is from
 * the old folder must never be forgotten for being absent from the new one: that dropped the row and deleted its
 * transcript, notes, star and tags. Only a row that still has its device copy may lose the stale Drive reference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveRepairKeepsDriveOnlyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val drive = "content://com.google.android.apps.docs.storage"
    private val oldFolder = "$drive/tree/acc%3D1%3Bdoc%3Dencoded%3Dold"
    private val newFolder = "$drive/tree/acc%3D1%3Bdoc%3Dencoded%3Dnew"
    private val driveOnly = "20260101_101010.000+0200_in_0501111111.ogg"
    private val withLocal = "20260101_111010.000+0200_in_0502222222.ogg"
    private val moved = "20260101_121010.000+0200_in_0503333333.ogg"

    private fun oldDoc(name: String) = "$oldFolder/document/acc%3D1%3Bdoc%3Dencoded%3D$name"
    private fun newDoc(name: String) = Uri.parse("$newFolder/document/acc%3D1%3Bdoc%3Dencoded%3D$name")
    private fun item(name: String) = RecordingItem(newDoc(name), name, 300L, 1L, null, null, null, number = null)

    @Before
    fun setUp(): Unit = runBlocking {
        AppPreferences(context).setDriveFolderUri(Uri.parse(newFolder))
        dao.upsert(RecordingEntry(driveOnly, driveUri = oldDoc(driveOnly), driveSizeBytes = 300L, lastModified = 5L))
        dao.upsert(RecordingEntry(withLocal, localUri = "content://dev/$withLocal", driveUri = oldDoc(withLocal), lastModified = 5L))
        dao.upsert(RecordingEntry(moved, driveUri = oldDoc(moved), driveSizeBytes = 300L, lastModified = 5L))
        mockkObject(RecordingsRepository)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        AppPreferences(context).setDriveFolderUri(null)
        listOf(driveOnly, withLocal, moved).forEach { dao.deleteByName(it) }
    }

    @Test
    fun `a new Drive folder keeps Drive-only rows and still re-points the ones it holds`() = runBlocking {
        every { RecordingsRepository.enumerateFolder(any(), any()) } returns listOf(item(moved))

        DriveCatalogRepair.reconcile(context)

        assertEquals("a Drive-only row must survive untouched", oldDoc(driveOnly), dao.findByName(driveOnly)?.driveUri)
        assertEquals(newDoc(moved).toString(), dao.findByName(moved)?.driveUri)
        val local = dao.findByName(withLocal)
        assertEquals("content://dev/$withLocal", local?.localUri)
        assertNull("a row with its device copy loses only the stale Drive reference", local?.driveUri)
    }

    @Test
    fun `a failed or empty listing forgets nothing`() = runBlocking {
        every { RecordingsRepository.enumerateFolder(any(), any()) } returns emptyList()

        DriveCatalogRepair.reconcile(context)

        assertEquals(oldDoc(driveOnly), dao.findByName(driveOnly)?.driveUri)
        assertEquals(oldDoc(withLocal), dao.findByName(withLocal)?.driveUri)
        assertEquals(oldDoc(moved), dao.findByName(moved)?.driveUri)
    }
}
