/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How a recording is named to the user.
 *
 * Every expectation is wrapped in [BidiText.isolate] because that is part of the rule, not decoration:
 * these names sit inside English sentences and must keep their own direction.
 *
 * One rule, in one place: contact, else number, else the file name. It was written out by hand at four
 * call sites, and the fourth got it wrong — the transcribing sheet showed
 * `20260819_201239.877+0300_in_2<name>.ogg` where every other surface said who the call was with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingLabelTest {

    private fun recording(
        displayName: String,
        contactName: String? = null,
        number: String? = null
    ) = RecordingItem(
        uri = Uri.parse("content://recordings/$displayName"),
        displayName = displayName,
        sizeBytes = 0L,
        lastModified = 0L,
        direction = null,
        displayDate = null,
        startedAtMillis = null,
        number = number,
        contactName = contactName
    )

    @Test
    fun prefers_the_contact_name() {
        assertEquals(BidiText.isolate("Dana"), RecordingLabel.of(recording("a.ogg", contactName = "Dana", number = "+972500000000")))
    }

    @Test
    fun falls_back_to_the_number_when_the_caller_is_not_a_contact() {
        assertEquals(BidiText.isolate("+972500000000"), RecordingLabel.of(recording("a.ogg", number = "+972500000000")))
    }

    @Test
    fun falls_back_to_the_file_name_when_nothing_else_is_known() {
        assertEquals(BidiText.isolate("a.ogg"), RecordingLabel.of(recording("a.ogg")))
    }

    @Test
    fun resolves_a_display_name_against_the_library() {
        val library = listOf(recording("a.ogg", contactName = "Dana"), recording("b.ogg", number = "+9721111111"))

        assertEquals(BidiText.isolate("Dana"), RecordingLabel.forDisplayName(library, "a.ogg"))
        assertEquals(BidiText.isolate("+9721111111"), RecordingLabel.forDisplayName(library, "b.ogg"))
    }

    @Test
    fun returns_the_display_name_when_the_recording_is_not_in_the_library() {
        // The transcribing sheet learns the name from WorkManager progress, which can outlive the
        // recording. Showing the raw name is right here; showing nothing would leave a blank sheet.
        assertEquals(BidiText.isolate("gone.ogg"), RecordingLabel.forDisplayName(emptyList(), "gone.ogg"))
    }

    // --- F5: the notification names the call from its own file name, never by listing the folder ---

    @Test
    fun `resolve prefers the contact looked up from the number`() {
        val label = RecordingLabel.resolve("20260916_101010.123+0300_in_+972500000000.ogg") { key ->
            if (key == "+972500000000") "Dana" else null
        }
        assertEquals(BidiText.isolate("Dana"), label)
    }

    @Test
    fun `resolve falls back to the number when no contact matches`() {
        val label = RecordingLabel.resolve("20260916_101010.123+0300_in_+972500000000.ogg") { null }
        assertEquals(BidiText.isolate("+972500000000"), label)
    }

    @Test
    fun `resolve falls back to the file label when the name has no number`() {
        assertEquals(RecordingLabel.forName("odd.ogg"), RecordingLabel.resolve("odd.ogg") { error("no lookup without a key") })
    }
}
