/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review N21 rework: the start-failure reason has to reach the app for ITS call, and only that call.
 * (4c) The app's stop can arrive while the slow VoIP start is still running; that stop finds no session and must not
 * wipe the reason the failed start leaves. (4d) A new app-call start clears whatever the previous call left.
 */
class CaptureDiagnosticsSlotTest {

    private class Session(private val diagnostics: String) : RecordingSession {
        override fun start() = Unit
        override fun stop() = Unit
        override fun captureDiagnostics() = diagnostics
    }

    @Test
    fun a_stop_with_no_session_keeps_the_start_failure() {
        val slot = CaptureDiagnosticsSlot()
        slot.startFailed(IllegalStateException("VoIP far-party sink unavailable (policy not armed?)"))
        slot.sessionEnded(null)
        assertEquals("IllegalStateException: VoIP far-party sink unavailable (policy not armed?)", VoipStartFailure.reasonOf(slot.value))
    }

    @Test
    fun a_stop_with_a_session_takes_its_health() {
        val slot = CaptureDiagnosticsSlot()
        slot.sessionEnded(Session("near late by 40 ms"))
        assertEquals("near late by 40 ms", slot.value)
    }

    @Test
    fun a_session_whose_health_cannot_be_read_leaves_nothing() {
        val slot = CaptureDiagnosticsSlot()
        slot.startFailed(IllegalStateException("old"))
        slot.sessionEnded(object : RecordingSession {
            override fun start() = Unit
            override fun stop() = Unit
            override fun captureDiagnostics(): String = throw IllegalStateException("boom")
        })
        assertEquals("", slot.value)
    }

    @Test
    fun an_accepted_start_clears_the_previous_calls_reason() {
        val slot = CaptureDiagnosticsSlot()
        slot.startFailed(IllegalStateException("from the call before"))
        slot.startAccepted()
        assertEquals("", slot.value)
    }

    @Test
    fun the_recorder_uses_the_slot() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val source = java.io.File(root, "com/baba/callvault/server/RecorderServiceImpl.kt").readText()
        assertEquals(false, source.contains("lastCaptureDiagnostics ="))
        assertEquals(true, source.contains("captureHealth.sessionEnded("))
        assertEquals(true, source.contains("captureHealth.startAccepted()"))
        assertEquals(true, source.contains("captureHealth.startFailed("))
    }
}
