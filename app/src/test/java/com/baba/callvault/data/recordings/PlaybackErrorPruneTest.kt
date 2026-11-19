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
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.system.storage.SafHelper
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A recording that failed to play (review C5) may be forgotten only on two proofs, as the rescan uses: it is a
 * device copy whose folder is still readable, and the document itself says "file not found". A lost folder grant,
 * a removed SD card or a Drive copy that would not open proves nothing, and forgetting the row would also delete
 * its transcript, notes, tags and star.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackErrorPruneTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261106_101010.123+0200_in_0501234567.ogg"
    // No provider answers this authority under Robolectric, so opening it fails with FileNotFoundException:
    // the same answer a deleted file gets from the real storage provider.
    private val tree = "content://com.example.missing.documents/tree/primary%3ARec"
    private val localUri = Uri.parse("$tree/document/primary%3ARec%2F$name")
    private val driveUri = Uri.parse("content://com.google.android.apps.docs.storage/tree/acc%3D1/document/acc%3D1%3Bdoc%3Dx")
    private val dao get() = RecordingDatabase.get(context).recordingDao()

    @Before
    fun setUp(): Unit = runBlocking {
        dao.upsert(RecordingEntry(name, localUri = localUri.toString(), driveUri = driveUri.toString(), lastModified = 1L))
        mockkObject(SafHelper)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        dao.deleteByName(name)
    }

    @Test
    fun `a device copy that is missing from a readable folder may be forgotten`() = runBlocking {
        every { SafHelper.isFolderValid(any(), any()) } returns true
        assertTrue(DeviceFolderRescan.isProvenMissingDeviceCopy(context, localUri))
    }

    @Test
    fun `a lost folder grant or a removed card keeps the row`() = runBlocking {
        every { SafHelper.isFolderValid(any(), any()) } returns false
        assertFalse(DeviceFolderRescan.isProvenMissingDeviceCopy(context, localUri))
    }

    @Test
    fun `a Drive copy that fails to play is never forgotten here`() = runBlocking {
        every { SafHelper.isFolderValid(any(), any()) } returns true
        assertFalse(DeviceFolderRescan.isProvenMissingDeviceCopy(context, driveUri))
    }

    @Test
    fun `a uri the catalog does not hold as a device copy is left alone`() = runBlocking {
        every { SafHelper.isFolderValid(any(), any()) } returns true
        assertFalse(DeviceFolderRescan.isProvenMissingDeviceCopy(context, Uri.parse("$tree/document/primary%3ARec%2Fother.ogg")))
    }
}
