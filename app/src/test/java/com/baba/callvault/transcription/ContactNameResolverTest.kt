/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R45: one name for the transcription prompt, without listing the library. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactNameResolverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `an app call carries its caller in the file name`() {
        assertEquals("Dana", ContactNameResolver.nameFor(context, "20260101_120000.000+0200_voip_Dana.ogg"))
    }

    @Test
    fun `a file with no name and no number has no contact`() {
        assertNull(ContactNameResolver.nameFor(context, "something-else.ogg"))
    }

    @Test
    fun `it names the same contact the recordings list does`() = runBlocking {
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
        val name = "20260101_120000.000+0200_voip_Dana.ogg"
        RecordingCatalog.recordLocal(context, name, "content://local/$name".toUri(), 10L, 100L)

        val listed = RecordingsRepository.listRecordings(context).first { it.displayName == name }.contactName

        assertEquals(listed, ContactNameResolver.nameFor(context, name))
    }
}
