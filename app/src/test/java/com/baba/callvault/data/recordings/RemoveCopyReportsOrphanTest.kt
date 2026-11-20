/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.db.RecordingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Removing a copy says which recording it just emptied, so its sidecars can follow (C41), and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoveCopyReportsOrphanTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clean(): Unit = runBlocking {
        val dao = RecordingDatabase.get(context).recordingDao()
        dao.getAll().forEach { dao.deleteByName(it.displayName) }
    }

    @Test
    fun `removing the last copy reports the recording name`() = runBlocking {
        val uri = "content://local/c41-a".toUri()
        RecordingCatalog.recordLocal(context, "c41-a.ogg", uri, 10L, 0L)

        val orphan = RecordingCatalog.removeCopyByUri(context, uri)

        assertEquals("c41-a.ogg", orphan)
    }

    @Test
    fun `removing one of two copies reports nothing`() = runBlocking {
        val local = "content://local/c41-b".toUri()
        RecordingCatalog.recordLocal(context, "c41-b.ogg", local, 10L, 0L)
        RecordingCatalog.markDrive(context, "c41-b.ogg", "content://drive/c41-b".toUri(), 10L, deleteLocalAfter = false)

        val orphan = RecordingCatalog.removeCopyByUri(context, local)

        assertNull(orphan)
    }

    @Test
    fun `an unknown uri reports nothing`() = runBlocking {
        assertNull(RecordingCatalog.removeCopyByUri(context, "content://local/none".toUri()))
    }
}
