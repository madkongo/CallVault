/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Review N21: an app-call capture that failed to start in the recorder was logged only there, so 23 lost calls on a
 * vivo V2546A left the user unaware and the always-on log without a reason. The recorder now hands the reason to the
 * app through the capture-diagnostics line, and the app tells the user and writes it to the always-on log.
 */
class VoipStartFailureTest {

    @Test
    fun the_line_carries_the_root_cause_and_reads_back() {
        val error = InvocationTargetException(IllegalStateException("VoIP far-party sink unavailable (policy not armed?)"))
        val line = VoipStartFailure.line(error)
        assertEquals("IllegalStateException: VoIP far-party sink unavailable (policy not armed?)", VoipStartFailure.reasonOf(line))
    }

    @Test
    fun a_cause_without_a_message_is_named_by_its_type() {
        assertEquals("NullPointerException", VoipStartFailure.reasonOf(VoipStartFailure.line(NullPointerException())))
    }

    @Test
    fun the_line_is_one_line_and_bounded() {
        val line = VoipStartFailure.line(RuntimeException("a\nb\r\n" + "x".repeat(2000)))
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertTrue(line.length <= VoipStartFailure.MAX_LINE)
    }

    @Test
    fun ordinary_capture_health_is_not_a_start_failure() {
        assertNull(VoipStartFailure.reasonOf(""))
        assertNull(VoipStartFailure.reasonOf(null))
        assertNull(VoipStartFailure.reasonOf("far side silent for 3 s; near late by 40 ms"))
    }

    /** The two ends of the hand-over, guarded in source: neither can be exercised without a daemon and a call. */
    @Test
    fun the_recorder_publishes_the_failure_and_the_app_reports_it() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val server = File(root, "com/baba/callvault/server/RecorderServiceImpl.kt").readText()
        assertTrue(server.contains("captureHealth.startFailed("))

        val app = File(root, "com/baba/callvault/services/recording/VoipRecordingCoordinator.kt").readText()
        val branch = app.substring(app.indexOf("if (!wroteAnything) {"), app.indexOf("} else if (!farHeard) {"))
        assertTrue(branch, branch.contains("VoipStartFailure.reasonOf("))
        assertTrue(branch, branch.contains("CallEndLog.record("))
        assertTrue(branch, branch.contains("VoipMissPolicy.tellStartFailure("))
        assertTrue(branch, branch.contains("reportMissed(context, R.string.voip_missed_capture_failed, null)"))
    }

    /** Review N21b: a newer recorder may add a recovery summary after a line break; the reason ends at the break. */
    @Test
    fun the_reason_stops_at_a_line_break() {
        val line = VoipStartFailure.line(IllegalStateException("sink unavailable")) + "\ncapture recovery: sink=ctor crashed(x)"
        assertEquals("IllegalStateException: sink unavailable", VoipStartFailure.reasonOf(line))
    }
}
