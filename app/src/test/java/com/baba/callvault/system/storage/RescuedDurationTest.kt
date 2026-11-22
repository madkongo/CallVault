/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.db.RecordingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review N9: a rescued recording's list length is its own, read from the file when the rescue saves it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RescuedDurationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private val uri = Uri.parse("content://x/tree/doc/rescued.ogg")

    private fun stored(name: String) = runBlocking { RecordingDatabase.get(context).recordingDao().findByName(name)?.durationSeconds }

    @Test
    fun `the probed length is stored, rounded to whole seconds`() = runBlocking {
        RecordingCatalog.recordLocal(context, "n9a.ogg", uri, 100L, 1L)
        RescuedDuration.store(context, "n9a.ogg", uri) { _, _ -> 128_400L }
        assertEquals(128L, stored("n9a.ogg"))
    }

    @Test
    fun `an unreadable file stores nothing and does not throw`() = runBlocking {
        RecordingCatalog.recordLocal(context, "n9b.ogg", uri, 100L, 1L)
        RescuedDuration.store(context, "n9b.ogg", uri) { _, _ -> 0L }
        RescuedDuration.store(context, "n9b.ogg", uri) { _, _ -> error("provider gone") }
        assertNull(stored("n9b.ogg"))
    }

    @Test
    fun `a stored length wins over the call log's, which is for one call only`() {
        assertEquals(128L, RecordingsRepository.preferredKnownSeconds(fromCallLog = 28L, stored = 128L))
        assertEquals(28L, RecordingsRepository.preferredKnownSeconds(fromCallLog = 28L, stored = null))
        assertEquals(128L, RecordingsRepository.preferredKnownSeconds(fromCallLog = null, stored = 128L))
        assertNull(RecordingsRepository.preferredKnownSeconds(null, null))
    }
}
