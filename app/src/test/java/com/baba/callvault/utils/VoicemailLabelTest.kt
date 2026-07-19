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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class VoicemailLabelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUpTelephony() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(telephonyManager).setVoiceMailNumber("123")
    }

    @Test
    fun matches_device_voicemail_number() {
        assertTrue(VoicemailLabel.isVoicemailNumber(context, "123"))
    }

    @Test
    fun does_not_match_regular_number() {
        assertFalse(VoicemailLabel.isVoicemailNumber(context, "0612345678"))
    }

    @Test
    fun null_or_blank_is_never_voicemail() {
        assertFalse(VoicemailLabel.isVoicemailNumber(context, null))
        assertFalse(VoicemailLabel.isVoicemailNumber(context, " "))
    }

    @Test
    fun without_phone_state_permission_is_never_voicemail() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.READ_PHONE_STATE)

        assertFalse(VoicemailLabel.isVoicemailNumber(context, "123"))
    }
}
