/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 92: what the setup journal must say at each setup screen, and when it may write or reopen. */
class SetupSnapshotTest {

    private fun facts(screen: String = "Permissions") = SetupSnapshot.Facts(
        screen = screen, mode = "STANDALONE", wirelessDebuggingRaw = "-1", wifi = "CONNECTED",
        romDisplay = "DP11.261030.003", romId = "DP11.261030.003", android = "Android 17 (API 37)",
        shizukuInstalled = "true", shizukuRunning = "false", shizukuPermission = "false", recorderConnected = "false",
    )

    @Test
    fun the_line_carries_every_fact_the_reviewer_asked_for() {
        val line = SetupSnapshot.format(facts())
        listOf(
            "Setup screen shown: Permissions", "mode=STANDALONE", "adb_wifi_enabled=-1", "wifi=CONNECTED",
            "rom=DP11.261030.003 (DP11.261030.003)", "Android 17 (API 37)",
            "shizuku: installed=true running=false permission=false", "recorder connected=false",
        ).forEach { assertTrue("missing '$it' in: $line", line.contains(it)) }
    }

    @Test
    fun each_setup_screen_is_named() {
        listOf("Disclaimer", "Permissions", "Wizard").forEach {
            assertTrue(SetupSnapshot.format(facts(it)).startsWith("Setup screen shown: $it |"))
        }
    }

    @Test
    fun the_screen_and_mode_lines_are_kept_and_never_thinned() {
        listOf(SetupSnapshot.TAG, SetupSnapshot.MODE_TAG).forEach {
            assertTrue(SetupJournalPolicy.shouldRecord(it, isClosed = false, bytesWritten = 0))
            assertTrue(SetupJournalPolicy.isNeverThinned(it))
        }
    }

    @Test
    fun a_mode_change_is_written_even_after_the_journal_ended_but_nothing_else_is() {
        assertTrue(SetupJournalPolicy.shouldRecord(SetupSnapshot.MODE_TAG, isClosed = true, bytesWritten = 0))
        assertFalse(SetupJournalPolicy.shouldRecord(SetupSnapshot.TAG, isClosed = true, bytesWritten = 0))
        assertFalse(SetupJournalPolicy.shouldRecord("CV:AdbShell", isClosed = true, bytesWritten = 0))
        // The size cap still holds for it.
        assertFalse(SetupJournalPolicy.shouldRecord(SetupSnapshot.MODE_TAG, isClosed = true, bytesWritten = SetupJournalPolicy.MAX_BYTES))
    }

    @Test
    fun a_mode_change_names_both_modes_and_what_triggered_it() {
        assertEquals(
            "Mode change: SHIZUKU -> STANDALONE, triggered by PermissionsContent.startSwitch",
            SetupSnapshot.modeChange("SHIZUKU", "STANDALONE", "PermissionsContent.startSwitch"),
        )
    }

    @Test
    fun the_trigger_is_the_first_caller_outside_the_backend() {
        val stack = arrayOf(
            StackTraceElement("com.baba.callvault.utils.SetupSnapshot", "triggerOf", "x", 1),
            StackTraceElement("com.baba.callvault.server.RecorderBackend", "switchTo", "x", 2),
            StackTraceElement("com.baba.callvault.server.RecorderBackend", "completeSwitch", "x", 3),
            StackTraceElement("kotlin.jvm.internal.Foo", "invoke", "x", 4),
            StackTraceElement("com.baba.callvault.ui.screens.PrivilegedModeSubSection", "onPick", "x", 5),
        )
        assertEquals("PrivilegedModeSubSection.onPick", SetupSnapshot.triggerOf(stack))
        assertEquals("unknown", SetupSnapshot.triggerOf(emptyArray()))
    }

    @Test
    fun a_sealed_journal_reopens_on_any_setup_screen_only_while_no_recorder_is_connected() {
        assertTrue(SetupJournal.mayReopen(recorderConnected = false))
        assertFalse(SetupJournal.mayReopen(recorderConnected = true))
    }

    @Test
    fun the_sealed_state_follows_the_last_marker_not_the_last_line() {
        val sealed = "a\n=== a recorder connected; setup finished [journal-end] ===\n"
        assertTrue(SetupJournal.isSealed(sealed))
        // A mode change written after the end marker must not make a restarted app think it is open.
        assertTrue(SetupJournal.isSealed(sealed + "2026 [I] CV:SetupMode: Mode change: SHIZUKU -> STANDALONE\n"))
        val reopened = sealed + "=== setup needed again (the Wizard screen is showing); journal reopened ===\n"
        assertFalse(SetupJournal.isSealed(reopened))
        // And sealing again after a reopen is seen.
        assertTrue(SetupJournal.isSealed(reopened + "=== a recorder connected; setup finished [journal-end] ===\n"))
        assertFalse(SetupJournal.isSealed("fresh journal\n"))
    }
}
