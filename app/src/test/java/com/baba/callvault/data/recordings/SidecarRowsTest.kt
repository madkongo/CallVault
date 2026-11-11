/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sidecar files (.md transcripts, .json details) are uploaded to Drive but must never become catalog
 * rows (BabApps item 6): they would otherwise reach retention, the health check and the library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SidecarRowsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio = "20261110_101010.000+0200_in_[C:abcd].ogg"
    private val md = "20261110_101010.000+0200_in_[C:abcd].md"
    private val json = "20261110_101010.000+0200_in_[C:abcd].json"
    private val dao get() = RecordingDatabase.get(context).recordingDao()

    @After
    fun tearDown() = runBlocking { listOf(audio, md, json).forEach { dao.deleteByName(it) } }

    @Test
    fun `drive sync does not create a row for a sidecar`() = runBlocking {
        RecordingCatalog.markDrive(context, md, "content://drive/md".toUri(), 10L, deleteLocalAfter = false)
        RecordingCatalog.markDrive(context, json, "content://drive/json".toUri(), 10L, deleteLocalAfter = false)
        assertNull(dao.findByName(md))
        assertNull(dao.findByName(json))
    }

    @Test
    fun `drive sync still creates and stamps a row for audio`() = runBlocking {
        RecordingCatalog.markDrive(context, audio, "content://drive/a".toUri(), 10L, deleteLocalAfter = false, lastModified = 5L)
        assertEquals("content://drive/a", dao.findByName(audio)?.driveUri)
    }

    @Test
    fun `stale sidecar rows are removed and recordings are kept`() = runBlocking {
        dao.upsert(RecordingEntry(md, driveUri = "content://drive/md"))
        dao.upsert(RecordingEntry(json, driveUri = "content://drive/json"))
        dao.upsert(RecordingEntry(audio, localUri = "content://local/a"))

        assertEquals(2, RecordingCatalog.removeStaleSidecarRows(context))

        assertNull(dao.findByName(md))
        assertNull(dao.findByName(json))
        assertNotNull(dao.findByName(audio))
        assertEquals(0, RecordingCatalog.removeStaleSidecarRows(context))
    }

    @Test
    fun `only sidecar names are stale`() {
        assertTrue(RecordingCatalog.isSidecarFile("x.MD"))
        assertTrue(RecordingCatalog.isSidecarFile("x.json"))
        assertFalse(RecordingCatalog.isSidecarFile("x.ogg"))
        assertFalse(RecordingCatalog.isSidecarFile("x.m4a"))
        // An unknown extension may be audio the folder scan took by MIME type: left alone.
        assertFalse(RecordingCatalog.isSidecarFile("x.bin"))
    }

    @Test
    fun `a recording with an extension not on the audio list still gets its Drive stamp`() = runBlocking {
        val odd = "20261110_101010.000+0200_in_[C:abcd].xyz"
        try {
            RecordingCatalog.recordLocal(context, odd, "content://local/odd".toUri(), 10L, 5L)
            RecordingCatalog.markDrive(context, odd, "content://drive/odd".toUri(), 10L, deleteLocalAfter = false)
            assertEquals("content://drive/odd", dao.findByName(odd)?.driveUri)
            // and the cleanup leaves it alone
            assertEquals(0, RecordingCatalog.removeStaleSidecarRows(context))
            assertNotNull(dao.findByName(odd))
        } finally {
            dao.deleteByName(odd)
        }
    }

    @Test
    fun `cleanup also finds upper-case sidecar extensions`() = runBlocking {
        val upper = "20261110_101010.000+0200_in_[C:abcd].MD"
        dao.upsert(RecordingEntry(upper, driveUri = "content://drive/up"))
        assertEquals(1, RecordingCatalog.removeStaleSidecarRows(context))
        assertNull(dao.findByName(upper))
    }
}
