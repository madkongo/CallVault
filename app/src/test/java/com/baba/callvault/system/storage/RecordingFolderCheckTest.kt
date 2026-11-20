/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review C98: the measurement behind "Home says Ready for a deleted folder". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingFolderCheckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ARecordings".toUri()

    @Before
    fun setUp() = RecordingFolderCheck.clearForTest(context)

    @After
    fun tearDown() {
        RecordingFolderCheck.clearForTest(context)
        AppPreferences(context).setRecordingFolderUri(null)
    }

    @Test
    fun `no folder set reads as not set, never as gone`() {
        AppPreferences(context).setRecordingFolderUri(null)
        assertEquals(FolderReading.Kind.NOT_SET, RecordingFolderCheck.measure(context).kind)
    }

    @Test
    fun `a folder CallVault holds no grant for reads as grant missing, with the evidence`() {
        AppPreferences(context).setRecordingFolderUri(folder)
        val reading = RecordingFolderCheck.measure(context)
        assertEquals(FolderReading.Kind.GRANT_MISSING, reading.kind)
        assertTrue(reading.evidence, reading.evidence.contains("write access"))
    }

    @Test
    fun `a stored confirmed loss is what readiness reads, for that folder only`() {
        AppPreferences(context).setRecordingFolderUri(folder)
        assertFalse(RecordingFolderCheck.isKnownUnusable(context, folder))
        RecordingFolderCheck.checkNow(context, "test")
        assertTrue(RecordingFolderCheck.isKnownUnusable(context, folder))
        assertFalse(RecordingFolderCheck.isKnownUnusable(context, "content://x/tree/other".toUri()))
    }
}
