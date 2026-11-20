/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N26: answering a call crashed the app (ForegroundServiceStartNotAllowedException) when nothing kept the
 * process in the foreground-start allow-list. The start must never throw into the call path.
 */
class ForegroundStartGuardTest {

    @Test
    fun `a start that works is reported as started`() {
        var called = 0
        val outcome = ForegroundStartGuard.attempt { called++ }
        assertEquals(1, called)
        assertEquals(ForegroundStartGuard.Outcome.Started, outcome)
    }

    @Test
    fun `a refused start is returned, not thrown`() {
        val refusal = IllegalStateException("startForegroundService() not allowed: service RecordingForegroundService")
        val outcome = ForegroundStartGuard.attempt { throw refusal }
        assertTrue(outcome is ForegroundStartGuard.Outcome.Refused)
        assertEquals(refusal, (outcome as ForegroundStartGuard.Outcome.Refused).cause)
    }

    @Test
    fun `a security refusal is also returned, not thrown`() {
        val outcome = ForegroundStartGuard.attempt { throw SecurityException("denied") }
        assertTrue(outcome is ForegroundStartGuard.Outcome.Refused)
    }

    @Test
    fun `the log line names the exception type and the message`() {
        val line = ForegroundStartGuard.describe("START_RECORDING", IllegalStateException("not allowed"), batteryExempt = false)
        assertTrue(line, line.contains("START_RECORDING"))
        assertTrue(line, line.contains("IllegalStateException"))
        assertTrue(line, line.contains("not allowed"))
        assertTrue(line, line.contains("batteryExempt=false"))
        assertFalse(line, line.contains("\n"))
    }

    @Test
    fun `the exemption warning shows only for a refusal the user can still fix`() {
        assertTrue(ForegroundStartGuard.shouldWarnAboutBattery(refusedAtMs = 5L, batteryExempt = false))
        assertFalse("exemption now granted", ForegroundStartGuard.shouldWarnAboutBattery(refusedAtMs = 5L, batteryExempt = true))
        assertFalse("never refused", ForegroundStartGuard.shouldWarnAboutBattery(refusedAtMs = 0L, batteryExempt = false))
    }

    @Test
    fun `describe tolerates a null message`() {
        assertNull(null)
        val line = ForegroundStartGuard.describe("STANDBY", RuntimeException(), batteryExempt = true)
        assertTrue(line, line.contains("RuntimeException"))
    }
}

class BatteryExemptionNeedTest {
    private fun need(
        mode: com.baba.callvault.data.PrivilegedMode = com.baba.callvault.data.PrivilegedMode.SHIZUKU,
        voip: Boolean = false, recording: Boolean = true, exempt: Boolean = false, sdk: Int = 34,
    ) = ForegroundStartGuard.setupNeedsBatteryExemption(mode, voip, recording, exempt, sdk)

    @Test fun `shizuku without app calls and no exemption needs it`() = assertTrue(need())
    @Test fun `exempt does not`() = assertFalse(need(exempt = true))
    @Test fun `app calls on means the keep-alive runs`() = assertFalse(need(voip = true))
    @Test fun `built-in mode keeps the keep-alive`() =
        assertFalse(need(mode = com.baba.callvault.data.PrivilegedMode.values().first { !it.needsShizuku }))
    @Test fun `recording off does not ask`() = assertFalse(need(recording = false))
    @Test fun `before Android 12 there is no limit`() = assertFalse(need(sdk = 30))
    @Test fun `the log line carries the quirk id`() =
        assertTrue(ForegroundStartGuard.describeSetup(com.baba.callvault.data.PrivilegedMode.SHIZUKU, false, 34).contains(ForegroundStartGuard.QUIRK_ID))
}
