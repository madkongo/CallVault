/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAlivePolicyTest {

    @Test
    fun `standalone always runs it - it keeps our own daemon alive`() {
        assertTrue(KeepAlivePolicy.shouldRun(PrivilegedMode.STANDALONE, voipEnabled = false))
        assertTrue(KeepAlivePolicy.shouldRun(PrivilegedMode.STANDALONE, voipEnabled = true))
    }

    @Test
    fun `shizuku runs it with app calls on and with app calls off`() {
        // Owner decision 2026-11-20: without a running keep-alive Android's unused-app hibernation stops the
        // app and revokes phone permissions (R4/Q54) and an answered call can hit the background foreground
        // start refusal (N26/Q53). The anchor is needed whether or not app calls are recorded.
        assertTrue(KeepAlivePolicy.shouldRun(PrivilegedMode.SHIZUKU, voipEnabled = false))
        assertTrue(KeepAlivePolicy.shouldRun(PrivilegedMode.SHIZUKU, voipEnabled = true))
    }

    @Test
    fun `every mode and app-calls combination runs, and only standalone may use adb`() {
        for (mode in PrivilegedMode.entries) for (voip in listOf(false, true)) {
            val d = KeepAlivePolicy.decision(mode, voip)
            assertTrue("mode=$mode voip=$voip", d.run)
            assertTrue("mode=$mode voip=$voip", d.reason.contains("mode=$mode") && d.reason.contains("appCalls=$voip"))
            assertEquals("mode=$mode voip=$voip", mode == PrivilegedMode.STANDALONE, d.mayUseAdb)
        }
    }

    @Test
    fun `the role of the keep-alive names what it does in each set-up`() {
        assertEquals(KeepAlivePolicy.Role.DAEMON_WATCHDOG, KeepAlivePolicy.decision(PrivilegedMode.STANDALONE, false).role)
        assertEquals(KeepAlivePolicy.Role.DAEMON_WATCHDOG, KeepAlivePolicy.decision(PrivilegedMode.STANDALONE, true).role)
        assertEquals(KeepAlivePolicy.Role.SHIZUKU_ANCHOR, KeepAlivePolicy.decision(PrivilegedMode.SHIZUKU, false).role)
        assertEquals(KeepAlivePolicy.Role.SHIZUKU_ANCHOR, KeepAlivePolicy.decision(PrivilegedMode.SHIZUKU, true).role)
    }

    @Test
    fun `the decision is logged once per change, not on every start`() {
        val log = KeepAliveDecisionLog()
        val a = KeepAlivePolicy.decision(PrivilegedMode.SHIZUKU, false)
        assertTrue(log.lineIfChanged(a)!!.contains("keep-alive runs"))
        assertNull("same decision again", log.lineIfChanged(a))
        assertNull(log.lineIfChanged(KeepAlivePolicy.decision(PrivilegedMode.SHIZUKU, false)))
        val b = KeepAlivePolicy.decision(PrivilegedMode.SHIZUKU, true)
        assertTrue("flag change is logged", log.lineIfChanged(b)!!.contains("appCalls=true"))
        assertTrue("mode change is logged", log.lineIfChanged(KeepAlivePolicy.decision(PrivilegedMode.STANDALONE, true))!!.contains("mode=STANDALONE"))
    }

    @Test
    fun `only standalone may use our own adb to recover`() {
        assertTrue(KeepAlivePolicy.mayUseAdb(PrivilegedMode.STANDALONE))
        assertFalse(KeepAlivePolicy.mayUseAdb(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun `the ADB actions the keep-alive may take are all of them in standalone and none in shizuku`() {
        assertEquals(KeepAlivePolicy.AdbAction.entries.toSet(), KeepAlivePolicy.allowedAdbActions(PrivilegedMode.STANDALONE))
        for (voip in listOf(false, true)) {
            assertTrue(KeepAlivePolicy.decision(PrivilegedMode.SHIZUKU, voip).adbActions.isEmpty())
        }
    }

    @Test
    fun `watchers are reconciled with the mode in both directions`() {
        val w = KeepAlivePolicy.WatcherAction.values()
        assertEquals(KeepAlivePolicy.WatcherAction.UNREGISTER, KeepAlivePolicy.reconcileWatchers(registered = true, mayUseAdb = false))
        assertEquals(KeepAlivePolicy.WatcherAction.REGISTER, KeepAlivePolicy.reconcileWatchers(registered = false, mayUseAdb = true))
        assertEquals(KeepAlivePolicy.WatcherAction.NONE, KeepAlivePolicy.reconcileWatchers(registered = true, mayUseAdb = true))
        assertEquals(KeepAlivePolicy.WatcherAction.NONE, KeepAlivePolicy.reconcileWatchers(registered = false, mayUseAdb = false))
        assertEquals(3, w.size)
    }

    @Test
    fun `every ADB-mutating call in the service sits behind usesAdb`() {
        val lines = java.io.File("src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt").readLines()
        val risky = listOf(
            "reviveAdbdIfStopped(", "enableWirelessDebugging(", "dropConnection(", "UsbDebuggingRestorer.onUsbDebuggingOff(",
            "approveCurrentNetworkIfWanted(", "contentResolver.registerContentObserver(",
        )
        val unguarded = lines.withIndex().filter { (i, line) ->
            val code = line.trim()
            val guarded = { l: String -> l.contains("usesAdb()") || l.contains("mayUseAdb") }
            val fnStart = (i downTo 0).firstOrNull { lines[it].trimStart().startsWith("private fun ") } ?: 0
            // Guarded close by, or by a guard in the first lines of the enclosing function (maybeRewarm returns early).
            !code.startsWith("*") && !code.startsWith("//") && risky.any { code.contains(it) } &&
                lines.subList(maxOf(0, i - 30), i + 1).none(guarded) &&
                lines.subList(fnStart, minOf(lines.size, fnStart + 6)).none(guarded)
        }.map { "${it.index + 1}: ${it.value.trim()}" }
        assertTrue("ADB calls with no usesAdb() guard in the 30 lines before: $unguarded", unguarded.isEmpty())
    }
}
