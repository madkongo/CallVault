/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deciding whether a capture that was asked for ever actually began.
 *
 * This exists because of a hole issue #28's reporter fell into. `startRecording` returns void and
 * does its real work on a worker afterwards, so a codec that will not configure — he hit it by
 * switching to AAC — throws inside the daemon, is logged in a process with no log file, and leaves
 * the app believing it started. He got no recording, no error, and nothing in the report he sent.
 *
 * `isRecording()` has been in the AIDL the whole time and was never asked. The rules below are what
 * to do with the answer, and the subtleties are all about NOT crying wolf: a start that is merely
 * slow, or a daemon that has already gone, must not be reported as a codec failure.
 */
class CaptureStartCheckTest {

    @Test
    fun `a capture that is running is fine`() {
        assertEquals(
            CaptureStartCheck.Verdict.STARTED,
            CaptureStartCheck.verdict(daemonReachable = true, isRecording = true, stopRequested = false),
        )
    }

    @Test
    fun `a reachable daemon that is not recording means the start failed`() {
        // The AAC case: the daemon is alive and well, it simply never got a capture going.
        assertEquals(
            CaptureStartCheck.Verdict.NEVER_STARTED,
            CaptureStartCheck.verdict(daemonReachable = true, isRecording = false, stopRequested = false),
        )
    }

    @Test
    fun `an unreachable daemon is not reported as a failed start`() {
        // A dead daemon is a different failure with its own message and its own watch. Reporting it
        // here as well would give the user two contradictory errors for one event.
        assertEquals(
            CaptureStartCheck.Verdict.UNKNOWN,
            CaptureStartCheck.verdict(daemonReachable = false, isRecording = false, stopRequested = false),
        )
    }

    @Test
    fun `a call that ended before the check is not a failure`() {
        // Short calls finish before the check fires. The capture is stopped by then and reporting
        // that as a broken codec would be wrong on every missed call.
        assertEquals(
            CaptureStartCheck.Verdict.STOPPED,
            CaptureStartCheck.verdict(daemonReachable = true, isRecording = false, stopRequested = true),
        )
        assertEquals(
            CaptureStartCheck.Verdict.STOPPED,
            CaptureStartCheck.verdict(daemonReachable = true, isRecording = true, stopRequested = true),
        )
    }

    @Test
    fun `only a definite never-started warrants telling the user`() {
        assertEquals(true, CaptureStartCheck.Verdict.NEVER_STARTED.isReportable)
        listOf(
            CaptureStartCheck.Verdict.STARTED,
            CaptureStartCheck.Verdict.STOPPED,
            CaptureStartCheck.Verdict.UNKNOWN,
        ).forEach { assertEquals("$it must stay quiet", false, it.isReportable) }
    }

    // ---- Review C2 second pass: "recording" alone is not proof that THIS call is recorded ----

    @Test
    fun `recording with an empty file is not called started - first a second look`() {
        assertEquals(
            CaptureStartCheck.Verdict.LOOK_AGAIN,
            CaptureStartCheck.verdict(daemonReachable = true, isRecording = true, stopRequested = false, bytesWritten = 0L),
        )
    }

    @Test
    fun `still empty at 8 s while the recorder records is a slow start, not a failure`() {
        // Gate review item 4: Bluetooth SCO, a cold Shizuku/scrcpy start. The old second look at 8 s called it failed.
        assertEquals(
            CaptureStartCheck.Verdict.LOOK_AGAIN,
            CaptureStartCheck.verdict(true, isRecording = true, stopRequested = false, bytesWritten = 0L, sinceStartMs = 8_000L),
        )
    }

    @Test
    fun `growth by a later look counts as started`() {
        assertEquals(CaptureStartCheck.Verdict.LOOK_AGAIN, CaptureStartCheck.verdict(true, true, false, bytesWritten = 0L, sinceStartMs = 3_000L))
        assertEquals(CaptureStartCheck.Verdict.STARTED, CaptureStartCheck.verdict(true, true, false, bytesWritten = 512L, sinceStartMs = 11_000L))
    }

    @Test
    fun `empty past the bound while recording is a failed start, reported`() {
        val v = CaptureStartCheck.verdict(true, true, false, bytesWritten = 0L, sinceStartMs = CaptureStartCheck.NOTHING_WRITTEN_AFTER_MS)
        assertEquals(CaptureStartCheck.Verdict.NOTHING_WRITTEN, v)
        assertEquals(true, v.isReportable)
    }

    @Test
    fun `empty and not recording is a failed start at any look`() {
        assertEquals(CaptureStartCheck.Verdict.NEVER_STARTED, CaptureStartCheck.verdict(true, false, false, bytesWritten = 0L, sinceStartMs = 3_000L))
    }

    @Test
    fun `bytes written is started, and an unreadable size falls back to the recorder's word`() {
        assertEquals(CaptureStartCheck.Verdict.STARTED, CaptureStartCheck.verdict(true, true, false, bytesWritten = 4096L))
        assertEquals(CaptureStartCheck.Verdict.STARTED, CaptureStartCheck.verdict(true, true, false, bytesWritten = null))
    }

    @Test
    fun `a short call that ended first is still never reported`() {
        assertEquals(CaptureStartCheck.Verdict.STOPPED, CaptureStartCheck.verdict(true, true, true, bytesWritten = 0L, sinceStartMs = 20_000L))
    }
}
