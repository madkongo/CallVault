/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "This setup cannot record right now" is not the same question in the two privileged modes.
 *
 * A Shizuku user never pairs anything, never turns Wireless debugging on and never grants
 * WRITE_SECURE_SETTINGS. Asking those of them would report "not ready" for ever on a phone that records
 * perfectly — and, worse, would *excuse* every missed call as their own doing, which is precisely the
 * reporting this project worked to make honest.
 *
 * Beside [SetupPrerequisitesTest] rather than inside it: that one drives the real `missing(context)`
 * under Robolectric and owns the health-store reporting seam. This one tests the decision itself over
 * plain facts, which is where the mode split lives.
 */
class SetupPrerequisitesModeTest {

    private val ready = ShizukuStatus.READY

    // ---- standalone: unchanged behaviour --------------------------------------------------------

    @Test
    fun standalone_still_wants_a_folder_first() {
        assertEquals(
            Prerequisite.RECORDING_FOLDER,
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.STANDALONE, hasFolder = false, isPaired = true,
                devOptionsDisabled = false, hasSecureSettings = true, daemonConnected = true,
                shizuku = ready,
            )
        )
    }

    @Test
    fun standalone_wants_pairing() {
        assertEquals(
            Prerequisite.ADB_PAIRING,
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.STANDALONE, hasFolder = true, isPaired = false,
                devOptionsDisabled = false, hasSecureSettings = true, daemonConnected = true,
                shizuku = ready,
            )
        )
    }

    @Test
    fun standalone_excuses_a_missing_grant_only_while_a_daemon_is_live() {
        assertNull(
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.STANDALONE, hasFolder = true, isPaired = true,
                devOptionsDisabled = false, hasSecureSettings = false, daemonConnected = true,
                shizuku = ready,
            )
        )
        assertEquals(
            Prerequisite.SECURE_SETTINGS_GRANT,
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.STANDALONE, hasFolder = true, isPaired = true,
                devOptionsDisabled = false, hasSecureSettings = false, daemonConnected = false,
                shizuku = ready,
            )
        )
    }

    // ---- shizuku: none of the ADB questions apply -----------------------------------------------

    @Test
    fun shizuku_mode_never_asks_for_pairing() {
        // The bug this prevents: an unpaired Shizuku user is not "not ready" — they are simply not
        // using the pairing path at all.
        assertNull(
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.SHIZUKU, hasFolder = true, isPaired = false,
                devOptionsDisabled = true, hasSecureSettings = false, daemonConnected = false,
                shizuku = ready,
            )
        )
    }

    @Test
    fun shizuku_mode_still_wants_a_folder() {
        // The one prerequisite about the user's own setup rather than about privileges.
        assertEquals(
            Prerequisite.RECORDING_FOLDER,
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.SHIZUKU, hasFolder = false, isPaired = false,
                devOptionsDisabled = false, hasSecureSettings = false, daemonConnected = false,
                shizuku = ready,
            )
        )
    }

    @Test
    fun a_stopped_shizuku_is_the_missing_prerequisite() {
        // Shizuku does not survive a reboot. That is a real, user-fixable reason a call went
        // unrecorded, and naming it is the difference between "start Shizuku" and a mystery.
        listOf(ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING, ShizukuStatus.NO_PERMISSION)
            .forEach { status ->
                assertEquals(
                    "$status should read as the missing prerequisite",
                    Prerequisite.SHIZUKU,
                    SetupPrerequisites.firstMissing(
                        mode = PrivilegedMode.SHIZUKU, hasFolder = true, isPaired = true,
                        devOptionsDisabled = false, hasSecureSettings = true, daemonConnected = false,
                        shizuku = status,
                    )
                )
            }
    }

    @Test
    fun a_live_recorder_outranks_a_shizuku_that_has_since_stopped() {
        // The user service outlives the app (bound with daemon(true)), so Shizuku's server going away
        // does not stop a recorder already connected — and calling that "not ready" would excuse a
        // genuine failure as the user's fault.
        assertNull(
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.SHIZUKU, hasFolder = true, isPaired = false,
                devOptionsDisabled = false, hasSecureSettings = false, daemonConnected = true,
                shizuku = ShizukuStatus.NOT_RUNNING,
            )
        )
    }

    // ---- a live daemon outranks the Developer-options flag (Android 17, issue #40) ----

    @Test
    fun a_connected_daemon_outranks_developer_options_being_reported_off() {
        // Recording is flowing over the daemon's binder right now. Calling setup incomplete here is what
        // wrote "this call was not recorded -- developer options were off" against calls that recorded
        // perfectly, on every Android 17 phone. The same rule already applies to Shizuku's status and to
        // the secure-settings grant; this line was the one that did not have it.
        assertEquals(
            null,
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.STANDALONE, hasFolder = true, isPaired = true,
                devOptionsDisabled = true, hasSecureSettings = true, daemonConnected = true,
                shizuku = ready,
            )
        )
    }

    @Test
    fun without_a_daemon_developer_options_being_off_is_still_reported() {
        // The guard must not swallow a real problem: with nothing recording, a proven-off Developer
        // options is exactly what the user needs told.
        assertEquals(
            Prerequisite.DEVELOPER_OPTIONS,
            SetupPrerequisites.firstMissing(
                mode = PrivilegedMode.STANDALONE, hasFolder = true, isPaired = true,
                devOptionsDisabled = true, hasSecureSettings = true, daemonConnected = false,
                shizuku = ready,
            )
        )
    }
}
