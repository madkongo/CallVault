/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class RecordingFileNameFormatterVoicemailTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setDeviceVoicemailNumber() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(telephonyManager).setVoiceMailNumber("123")
    }

    @Test
    fun contact_name_placeholder_falls_back_to_voicemail_label() {
        val metadata = RecordingMetadata(rawPhoneNumber = "123", direction = RecordingDirection.OUTGOING)

        val fileName = RecordingFileNameFormatter.formatFileName(
            context, metadata, ScrcpyAudioCodec.OPUS, customFormat = "{contact_name}"
        )

        assertEquals("Voicemail.ogg", fileName)
    }

    @Test
    fun contact_name_placeholder_falls_back_to_number_for_unsaved_number() {
        // No saved contact and not voicemail: the filename must still identify the call.
        val metadata = RecordingMetadata(rawPhoneNumber = "0612345678", direction = RecordingDirection.OUTGOING)

        val fileName = RecordingFileNameFormatter.formatFileName(
            context, metadata, ScrcpyAudioCodec.OPUS, customFormat = "{contact_name}"
        )

        assertEquals("0612345678.ogg", fileName)
    }

    @Test
    fun number_fallback_is_skipped_when_template_already_has_the_number() {
        // {phone_number} already carries the number — an unsaved contact must not duplicate it.
        val metadata = RecordingMetadata(rawPhoneNumber = "0612345678", direction = RecordingDirection.OUTGOING)

        val fileName = RecordingFileNameFormatter.formatFileName(
            context, metadata, ScrcpyAudioCodec.OPUS, customFormat = "{phone_number}_{contact_name}"
        )

        assertEquals("0612345678_.ogg", fileName)
    }

    @Test
    fun voicemail_label_still_wins_over_number_fallback() {
        val metadata = RecordingMetadata(rawPhoneNumber = "123", direction = RecordingDirection.OUTGOING)

        val fileName = RecordingFileNameFormatter.formatFileName(
            context, metadata, ScrcpyAudioCodec.OPUS, customFormat = "{contact_name}"
        )

        assertEquals("Voicemail.ogg", fileName)
    }
}
