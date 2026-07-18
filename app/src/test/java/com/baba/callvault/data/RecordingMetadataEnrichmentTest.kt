/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.utils.PhoneNumberManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class RecordingMetadataEnrichmentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun resetPhoneNumberManagerSingleton() {
        // The singleton holds the previous test's application context; reset it so each test's
        // TelephonyManager shadow (device country) is the one actually read.
        PhoneNumberManager::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private fun setDeviceCountry(iso: String) {
        val telephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(telephonyManager).setNetworkCountryIso(iso)
    }

    @Test
    fun short_code_is_not_standardized_and_keeps_raw_number() = runBlocking {
        // "123" is a carrier voicemail short code in FR — must not become "+33123".
        setDeviceCountry("fr")
        val base = RecordingMetadata(rawPhoneNumber = "123", direction = RecordingDirection.OUTGOING)

        val enriched = RecordingMetadata.enrichMetadata(context, base)

        assertNull(enriched.standardizedNumber)
        assertEquals("123", enriched.getBestNumber())
        assertFalse(enriched.isCrossCountry)
    }

    @Test
    fun valid_national_number_is_standardized_to_e164() = runBlocking {
        setDeviceCountry("fr")
        val base = RecordingMetadata(rawPhoneNumber = "0612345678", direction = RecordingDirection.OUTGOING)

        val enriched = RecordingMetadata.enrichMetadata(context, base)

        assertEquals("+33612345678", enriched.standardizedNumber)
        assertEquals("+33612345678", enriched.getBestNumber())
    }

    @Test
    fun invalid_foreign_number_still_counts_as_cross_country() = runBlocking {
        // A parseable-but-invalid number with a foreign country code must keep its raw form AND
        // stay cross-country, so the ignore-cross-country recording rules still apply to it.
        setDeviceCountry("us")
        val base = RecordingMetadata(rawPhoneNumber = "+33123", direction = RecordingDirection.OUTGOING)

        val enriched = RecordingMetadata.enrichMetadata(context, base)

        assertNull(enriched.standardizedNumber)
        assertEquals("+33123", enriched.getBestNumber())
        assertTrue(enriched.isCrossCountry)
    }
}
