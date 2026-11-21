/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review C131: which capture path a call took, and why it left the fast one, reaches the app's log. */
class CaptureRouteTest {

    private class Session(private val diagnostics: String) : RecordingSession {
        override fun start() = Unit
        override fun stop() = Unit
        override fun captureDiagnostics() = diagnostics
    }

    @Test
    fun a_normal_call_says_direct() {
        assertEquals("path=direct", CaptureRoute.direct())
    }

    @Test
    fun a_call_that_fell_back_says_why() {
        val line = CaptureRoute.scrcpyAfterDirectFailure(IllegalStateException("AudioRecord failed to enter RECORDING state"))
        assertEquals("path=scrcpy (direct failed: IllegalStateException: AudioRecord failed to enter RECORDING state)", line)
    }

    @Test
    fun a_direct_failure_without_a_message_is_still_named() {
        assertEquals("path=scrcpy (direct failed: RuntimeException)", CaptureRoute.scrcpyAfterDirectFailure(RuntimeException()))
    }

    @Test
    fun a_phone_without_direct_support_says_so() {
        assertEquals("path=scrcpy (direct capture not supported here)", CaptureRoute.scrcpyDirectUnsupported())
    }

    @Test
    fun a_long_reason_is_bounded_and_one_line() {
        val line = CaptureRoute.scrcpyAfterDirectFailure(IllegalStateException("a\nb" + "x".repeat(500)))
        assertTrue(line, !line.contains('\n'))
        assertTrue(line, line.length < 200)
    }

    @Test
    fun the_route_leads_the_capture_health_and_the_health_is_kept() {
        val slot = CaptureDiagnosticsSlot(CaptureRecoveryLog())
        slot.sessionEnded(Session("overrunMs=40 overruns=1"), CaptureRoute.direct())
        assertEquals("path=direct\noverrunMs=40 overruns=1", slot.value)
    }

    @Test
    fun a_healthy_call_still_carries_its_route() {
        val slot = CaptureDiagnosticsSlot(CaptureRecoveryLog())
        slot.sessionEnded(Session(""), CaptureRoute.direct())
        assertEquals("path=direct", slot.value)
    }

    @Test
    fun the_levels_line_still_parses_behind_a_route() {
        val slot = CaptureDiagnosticsSlot(CaptureRecoveryLog())
        slot.sessionEnded(Session("capture levels: ch=2 ms=61000 p0=900 p1=1200"), CaptureRoute.direct())
        val levels = CaptureLevels.parse(slot.value)!!
        assertEquals(900, levels.peak0)
        assertEquals("path=direct", CaptureLevels.strip(slot.value))
    }

    @Test
    fun a_session_with_no_route_behaves_as_before() {
        val slot = CaptureDiagnosticsSlot(CaptureRecoveryLog())
        slot.sessionEnded(Session("near late by 40 ms"))
        assertEquals("near late by 40 ms", slot.value)
    }
}
