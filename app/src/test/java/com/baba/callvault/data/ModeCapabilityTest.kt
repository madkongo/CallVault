/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each privileged mode can actually do.
 *
 * The list is not a matter of taste: it follows from one measured fact — a Shizuku-hosted process cannot
 * get an `AudioRecord` into RECORDING state, so capture there happens through scrcpy, which spawns its
 * own process. Everything that needs a privileged `AudioRecord` in the recorder process is therefore
 * unavailable, and everything that needs our embedded ADB has nothing to talk to.
 */
class ModeCapabilityTest {

    @Test
    fun standalone_can_do_everything() {
        ModeCapability.entries.forEach {
            assertTrue("$it should work in standalone", it.isAvailableIn(PrivilegedMode.STANDALONE))
        }
    }

    @Test
    fun shizuku_cannot_do_anything_that_needs_a_privileged_audiorecord() {
        // All three hand a daemon-created AudioRecord around; none can work where one cannot start.
        assertFalse(ModeCapability.RESILIENT_RECORDING.isAvailableIn(PrivilegedMode.SHIZUKU))
        assertFalse(ModeCapability.VOIP_RECORDING.isAvailableIn(PrivilegedMode.SHIZUKU))
        assertFalse(ModeCapability.SPEAKER_ATTRIBUTION.isAvailableIn(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun shizuku_cannot_do_anything_that_needs_our_own_adb() {
        assertFalse(ModeCapability.OFFLINE_RECORDING.isAvailableIn(PrivilegedMode.SHIZUKU))
        assertFalse(ModeCapability.WIRELESS_DEBUGGING_CONTROL.isAvailableIn(PrivilegedMode.SHIZUKU))
        assertFalse(ModeCapability.DAEMON_KEEP_ALIVE.isAvailableIn(PrivilegedMode.SHIZUKU))
        assertFalse(ModeCapability.SILENT_UPDATE_INSTALL.isAvailableIn(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun carrier_recording_itself_works_in_both() {
        // The whole point: a call still records under Shizuku, through scrcpy.
        assertTrue(ModeCapability.CARRIER_RECORDING.isAvailableIn(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun transcription_and_storage_are_untouched_by_the_mode() {
        // They read finished files and never go near the recorder, so a mode switch must not disturb
        // them — listing them keeps that explicit rather than assumed.
        assertTrue(ModeCapability.TRANSCRIPTION.isAvailableIn(PrivilegedMode.SHIZUKU))
        assertTrue(ModeCapability.CLOUD_SYNC.isAvailableIn(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun the_unavailable_set_is_exactly_the_features_that_report_unavailable() {
        val expected = ModeCapability.entries.filterNot { it.isAvailableIn(PrivilegedMode.SHIZUKU) }.toSet()

        assertEquals(expected, ModeCapability.unavailableIn(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun nothing_is_unavailable_in_standalone() {
        assertTrue(ModeCapability.unavailableIn(PrivilegedMode.STANDALONE).isEmpty())
    }

    // ---- Android version (backlog #1; evidence: docs/dev-notes/2026-09-29-feature-android-version-map.md)

    @Test
    fun app_calls_need_android_14() {
        // Before 14 the shell has no CAPTURE_VOICE_COMMUNICATION_OUTPUT: coonrw's A12 and #42's A13 failed.
        assertFalse(ModeCapability.VOIP_RECORDING.isAvailableOn(33))
        assertTrue(ModeCapability.VOIP_RECORDING.isAvailableOn(34))
    }

    @Test
    fun resilient_recording_needs_android_13() {
        // Captured 0 bytes on coonrw's Android 12; worked on Android 13 and newer.
        assertFalse(ModeCapability.RESILIENT_RECORDING.isAvailableOn(32))
        assertTrue(ModeCapability.RESILIENT_RECORDING.isAvailableOn(33))
    }

    @Test
    fun everything_else_works_from_the_oldest_supported_android() {
        val gated = setOf(ModeCapability.VOIP_RECORDING, ModeCapability.RESILIENT_RECORDING)
        ModeCapability.entries.filterNot { it in gated }.forEach {
            assertTrue("$it should work on Android 11", it.isAvailableOn(30))
        }
    }

    @Test
    fun a_capability_needs_both_the_mode_and_the_version() {
        assertTrue(ModeCapability.VOIP_RECORDING.isAvailable(PrivilegedMode.STANDALONE, 34))
        assertFalse(ModeCapability.VOIP_RECORDING.isAvailable(PrivilegedMode.STANDALONE, 33))
        assertFalse(ModeCapability.VOIP_RECORDING.isAvailable(PrivilegedMode.SHIZUKU, 36))
    }

    @Test
    fun the_reason_names_the_android_release_not_the_api_level() {
        assertEquals("14", ModeCapability.VOIP_RECORDING.minAndroidRelease)
        assertEquals("13", ModeCapability.RESILIENT_RECORDING.minAndroidRelease)
    }
}
