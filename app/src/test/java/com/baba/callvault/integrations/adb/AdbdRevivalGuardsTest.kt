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

    // --- H2 / R2 ---

    private val stable = AdbdRevivalBackoff.STABLE_RUNNING_MS

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
    fun `a cycle that worked still spaces the next one, so a rule that stops adbd again does not ping-pong (R2)`() {
        val b = AdbdRevivalBackoff()
        b.onCycleEnded(now = 0, adbdRunning = true)
        // adbd is RUNNING right after the cycle, then stops again seconds later
        b.onRunningSeen(now = 5_000)
        b.onNotRunningSeen()
        assertFalse(b.mayCycle(60_000))
        assertTrue(b.mayCycle(tenMin))
    }

    @Test
    fun `a brief running does not clear the back-off, only adbd staying running for several minutes does`() {
        val b = AdbdRevivalBackoff()
        b.onCycleEnded(now = 0, adbdRunning = false)
        b.onRunningSeen(now = 1_000)
        assertFalse(b.mayCycle(2_000))
        b.onRunningSeen(now = 1_000 + stable)
        assertTrue(b.mayCycle(1_000 + stable))
        // and the next failure starts from the base period again
        b.onCycleEnded(now = 5_000_000, adbdRunning = false)
        assertEquals(5_000_000 + tenMin, b.nextAllowedMs)
    }

    @Test
    fun `a stop in between restarts the running clock`() {
        val b = AdbdRevivalBackoff()
        b.onCycleEnded(now = 0, adbdRunning = false)
        b.onRunningSeen(now = 1_000)
        b.onNotRunningSeen()
        b.onRunningSeen(now = 1_000 + stable)
        assertFalse(b.mayCycle(1_000 + stable))
    }

    @Test
    fun `at most three cycles an hour whatever their outcome, and the cap says why`() {
        val b = AdbdRevivalBackoff()
        var t = 0L
        repeat(AdbdRevivalBackoff.MAX_CYCLES_PER_WINDOW) {
            assertTrue(b.mayCycle(t))
            b.onCycleEnded(now = t, adbdRunning = true)
            b.onRunningSeen(now = t + 1)
            b.onRunningSeen(now = t + 1 + stable) // stable running clears the spacing, not the cap
            t += stable + 10_000
        }
        assertFalse(b.mayCycle(t))
        assertTrue(b.blockReason(t)!!.contains("per hour"))
        assertTrue(b.mayCycle(310_000 + AdbdRevivalBackoff.WINDOW_MS + 1))
    }

    @Test
    fun `blockReason is null when a cycle may run`() {
        assertNull(AdbdRevivalBackoff().blockReason(0))
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
        assertTrue(body.contains("blockReason("))
        assertTrue(body.contains("backing off"))
        // vetoed before the settle wait and again before the first write of the cycle
        assertTrue(body.indexOf("vetoCycle()") in 0 until body.indexOf("Thread.sleep(REVIVE_SETTLE_MS)"))
        assertTrue(body.lastIndexOf("vetoCycle()") < body.indexOf("markOwnWirelessDebuggingWrite(0)"))
        assertTrue(src.contains("onCycleEnded("))
    }

    @Test
    fun `the cycle asks whether a call is up without the update installer's wording`() {
        val src = File(listOf(
            "src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
            "app/src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
        ).first { File(it).exists() }).readText()
        assertFalse(src.contains("CallInProgressGate.mayInstall("))
        assertTrue(src.contains("CallInProgressGate.isCallInProgress("))
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
