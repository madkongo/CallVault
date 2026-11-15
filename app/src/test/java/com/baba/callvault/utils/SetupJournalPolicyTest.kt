/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the always-on setup journal is allowed to keep.
 *
 * The journal exists because the debug log is off by default, so the very first setup — the one that
 * fails on phones we do not own — is never recorded. Issue #23 is exactly that: a reporter stuck from
 * the first minute, with nothing to send. Every rule here is a guard on a log that no one opted into,
 * so it stays narrow: setup and transport only, bounded, and finished for good once setup works.
 */
class SetupJournalPolicyTest {

    @Test
    fun the_setup_and_transport_path_is_kept() {
        listOf("CV:AdbMdns", "CV:AdbShell", "CV:RecorderLauncher", "CV:RecorderBackend").forEach {
            assertTrue("$it belongs in the journal", SetupJournalPolicy.shouldRecord(it, isClosed = false, bytesWritten = 0))
        }
    }

    @Test
    fun the_pairing_service_logs_under_a_tag_the_journal_keeps() {
        // BabApps item 92: the service logged as "AdbPairingService", which was not on the list, so a setup
        // stuck at pairing left no pairing line in the journal. Tying the test to the service's own constant
        // means a rename cannot silently drop it again.
        assertTrue(
            SetupJournalPolicy.shouldRecord(
                com.baba.callvault.integrations.adb.AdbPairingService.TAG, isClosed = false, bytesWritten = 0
            )
        )
    }

    @Test
    fun everything_about_calls_and_their_content_is_refused() {
        // The journal is written without anyone asking for it, so what it may hold is decided here and
        // not at the call sites. Anything that touches a call, its audio or its text stays out.
        listOf("CV:VoipRec", "CV:TranscriptRepository", "CV:AudioRecordingEngine", "CV:CallLogReader",
               "CV:RecordingsRepo", "CV:PhoneNumberManager").forEach {
            assertFalse("$it must never reach a log the user did not switch on",
                SetupJournalPolicy.shouldRecord(it, isClosed = false, bytesWritten = 0))
        }
    }

    @Test
    fun the_chatty_keep_alive_is_left_out_so_it_cannot_crowd_the_first_run_out() {
        // It re-warms on a timer for as long as the app lives; at a fixed cap it would fill the file
        // with a loop and push out the one thing the journal is for.
        assertFalse(SetupJournalPolicy.shouldRecord("CV:DaemonKeepAlive", isClosed = false, bytesWritten = 0))
    }

    @Test
    fun the_cap_stops_it_rather_than_rotating_it() {
        // Keeping the FIRST run is the whole point: a rotation would discard exactly the part that
        // explains a phone that never worked, in favour of the hundredth healthy launch.
        assertTrue(SetupJournalPolicy.shouldRecord("CV:AdbMdns", isClosed = false, bytesWritten = SetupJournalPolicy.MAX_BYTES - 1))
        assertFalse(SetupJournalPolicy.shouldRecord("CV:AdbMdns", isClosed = false, bytesWritten = SetupJournalPolicy.MAX_BYTES))
    }

    @Test
    fun once_setup_has_worked_the_journal_is_finished() {
        assertFalse(SetupJournalPolicy.shouldRecord("CV:AdbMdns", isClosed = true, bytesWritten = 0))
    }

    @Test
    fun an_unknown_tag_is_refused_rather_than_allowed() {
        // A new tag joins the journal by being named here, never by accident.
        assertFalse(SetupJournalPolicy.shouldRecord("CV:SomethingNew", isClosed = false, bytesWritten = 0))
    }
}
