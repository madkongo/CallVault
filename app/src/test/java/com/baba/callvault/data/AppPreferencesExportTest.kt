/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPreferencesExportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `device-specific keys are never exportable`() {
        listOf(
            "adb_paired", "loopback_adb_port", "privileged_mode", "recording_folder_uri",
            "drive_folder_uri", "log_pseudonym_salt", "wizard_completed", "disclaimer_accepted",
            "available_update_tag", "wd_enabled_by_us", "shell_grant_state", "classic_settings_screen",
            "adb_last_connect_port", "debug_skip_mdns_connect",
        ).forEach { key ->
            assertFalse("$key must not be exportable", AppPreferences.EXPORTABLE_KEYS.contains(key))
        }
    }

    @Test
    fun `the last good connect port is kept on this phone and left out of the export (#65)`() {
        val prefs = AppPreferences(context)
        assertEquals(null, prefs.getLastAdbConnectPort())
        prefs.setLastAdbConnectPort(41_234)
        assertEquals(41_234, AppPreferences(context).getLastAdbConnectPort())
        assertFalse(prefs.exportToJson().contains("adb_last_connect_port"))
        prefs.setLastAdbConnectPort(0)
        assertEquals("0 is no port", null, prefs.getLastAdbConnectPort())
    }

    @Test
    fun `the skip-mDNS test switch is off by default and not exported`() {
        val prefs = AppPreferences(context)
        assertFalse(prefs.isDebugSkipMdnsConnect())
        prefs.setDebugSkipMdnsConnect(true)
        assertTrue(AppPreferences(context).isDebugSkipMdnsConnect())
        assertFalse(prefs.exportToJson().contains("debug_skip_mdns_connect"))
    }

    @Test
    fun `real user preferences are exportable`() {
        listOf(
            "file_name_template", "theme_mode", "audio_codec", "voip_recording_enabled",
            "carrier_recording_enabled", "write_transcript_sidecar",
        ).forEach { key ->
            assertTrue("$key should be exportable", AppPreferences.EXPORTABLE_KEYS.contains(key))
        }
    }

    @Test
    fun `export then import round-trips an exportable setting`() {
        val prefs = AppPreferences(context)
        prefs.setFileNameTemplate("{contact_name}_{date}")
        prefs.setWriteMetadataFileEnabled(true)

        val json = prefs.exportToJson()
        assertTrue("recording_folder_uri must not be in the export", !json.contains("recording_folder_uri"))

        // Change the live values, then restore from the exported JSON.
        prefs.setFileNameTemplate("{date}_{phone_number}")
        prefs.setWriteMetadataFileEnabled(false)

        val applied = prefs.importFromJson(json)
        assertTrue("some settings should have applied", applied > 0)
        assertEquals("{contact_name}_{date}", prefs.getFileNameTemplate())
        assertTrue(prefs.isWriteMetadataFileEnabled())
    }

    @Test
    fun `the export declares the app and a format version`() {
        val root = JSONObject(AppPreferences(context).exportToJson())
        assertEquals("CallVault", root.getString("app"))
        assertTrue(root.has("settings"))
    }

    @Test
    fun `importing malformed json returns -1`() {
        assertEquals(-1, AppPreferences(context).importFromJson("this is not json"))
    }
}
