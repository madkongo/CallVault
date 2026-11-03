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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A copy that reached Drive is recorded as such even when the job is being stopped.
 *
 * On a Honor BVL-N49 (2026-11-03) the scheduled copy was stopped right after a file landed in Drive, and
 * the log said "markDrive(...) failed: Job was cancelled": the recording stayed "not in Drive" for the
 * health check, which is what the "not reaching Drive" warning counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkDriveNonCancellableTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261103_101010.000+0200_in_[C:abcd].ogg"

    @After
    fun tearDown() = runBlocking { RecordingCatalog.removeName(context, name) }

    @Test
    fun `a job that is being stopped still records the Drive copy`() = runBlocking {
        RecordingCatalog.recordLocal(context, name, "content://local/1".toUri(), 10L, 0L)

        launch {
            cancel() // the worker is stopped just as the copy finished
            RecordingCatalog.markDrive(context, name, "content://drive/1".toUri(), 10L, deleteLocalAfter = false)
        }.join()

        val row = RecordingDatabase.get(context).recordingDao().findByName(name)
        assertEquals("content://drive/1", row?.driveUri)
    }
}
