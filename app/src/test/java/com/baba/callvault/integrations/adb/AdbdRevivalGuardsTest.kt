/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back-off after a cycle that did not bring adbd back (H2) and the live-call guard (M4). */
class AdbdRevivalGuardsTest {

    private val tenMin = AdbdRevivalBackoff.BASE_MS

    // --- H2 ---

    @Test
    fun `the first cycle is always allowed`() {
        assertTrue(AdbdRevivalBackoff().mayCycle(0))
    }

    @Test
    fun `a cycle that left adbd not running blocks the next one for the base period, then doubles, up to the cap`() {
        val b = AdbdRevivalBackoff()
        b.onCycleEnded(now = 0, adbdRunning = false)
        assertFalse(b.mayCycle(tenMin - 1))
        assertTrue(b.mayCycle(tenMin))
        b.onCycleEnded(now = tenMin, adbdRunning = false)
        assertEquals(tenMin + tenMin * 2, b.nextAllowedMs)
        repeat(10) { b.onCycleEnded(now = b.nextAllowedMs, adbdRunning = false) }
        assertEquals(AdbdRevivalBackoff.CAP_MS, b.lastDelayMs)
    }

    @Test
    fun `a cycle that worked, or a running adbd seen later, clears the back-off`() {
        val b = AdbdRevivalBackoff()
        b.onCycleEnded(now = 0, adbdRunning = false)
        b.onRunningSeen()
        assertTrue(b.mayCycle(1))
        b.onCycleEnded(now = 0, adbdRunning = false)
        b.onCycleEnded(now = 1, adbdRunning = true)
        assertTrue(b.mayCycle(2))
        // and the next failure starts again from the base period
        b.onCycleEnded(now = 5, adbdRunning = false)
        assertEquals(5 + tenMin, b.nextAllowedMs)
    }

    // --- M4 ---

    @Test
    fun `no cycle during a call or while a recorder is connected`() {
        assertNotNull(AdbdCycleGuard.blockReason(callInProgress = true, recorderConnected = false))
        assertNotNull(AdbdCycleGuard.blockReason(callInProgress = false, recorderConnected = true))
        assertNull(AdbdCycleGuard.blockReason(callInProgress = false, recorderConnected = false))
    }

    @Test
    fun `the guard and the back-off are asked before a cycle, the guard's skip is logged`() {
        val src = File(listOf(
            "src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
            "app/src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
        ).first { File(it).exists() }).readText()
        val start = src.indexOf("private fun reviveLocked(")
        assertTrue(start > 0)
        val body = src.substring(start, src.indexOf("fun observeAdbd()", start))
        assertTrue(body.contains("AdbdCycleGuard.blockReason("))
        assertTrue(body.contains("mayCycle("))
        assertTrue(body.contains("backing off"))
        // vetoed before the settle wait and again before the first write of the cycle
        assertTrue(body.indexOf("vetoCycle()") in 0 until body.indexOf("Thread.sleep(REVIVE_SETTLE_MS)"))
        assertTrue(body.lastIndexOf("vetoCycle()") < body.indexOf("markOwnWirelessDebuggingWrite(0)"))
        assertTrue(src.contains("onCycleEnded("))
    }

    @Test
    fun `the revive lock is entered with a bound so a call start never queues behind a cycle`() {
        val src = File(listOf(
            "src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
            "app/src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
        ).first { File(it).exists() }).readText()
        assertFalse(src.contains("synchronized(reviveLock)"))
        assertTrue(src.contains("reviveLock.withLockOrElse("))
    }
}
