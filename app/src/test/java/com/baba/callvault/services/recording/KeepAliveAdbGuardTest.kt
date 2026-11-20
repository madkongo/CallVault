/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard (P1a): in Shizuku mode the keep-alive must never act on our own ADB. This reads the service source and
 * requires a REAL code guard (not a comment) just before every ADB-mutating call, in the same scope, and an early
 * return at the top of every callback that is registered only for ADB.
 */
class KeepAliveAdbGuardTest {

    private val lines = File("src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt").readLines()

    private fun isComment(l: String): Boolean {
        val t = l.trim()
        return t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }

    private fun code(l: String): String = l.substringBefore("//").let { if (isComment(l)) "" else it }

    private val risky = Regex(
        """AdbShell\.(reviveAdbd\w*|enable\w*|disable\w*|release\w*|drop\w*|approve\w*)\(""" +
            """|UsbDebuggingRestorer\.onUsbDebuggingOff\(|Settings\.Global\.put\w*\(|registerContentObserver\(""" +
            """|registerNetworkCallback\(|RecorderServerLauncher\.\w+\(|releaseWirelessDebugging""",
    )
    private val scopeStart = Regex("""\bfun\s|\bThread\s*\{|\bThread\s*\(\s*\{|Runnable\s*\{""")
    private val guard = Regex("""if\s*\(.*\busesAdb\(\)|adbStillAllowed\(""")
    private val inlineGuard = Regex("""mayEnable\s*=\s*usesAdb\(\)|needsAdb\s*=\s*usesAdb\(\)""")

    /** Functions whose only job is the registration; their single call site is checked separately. */
    private val registrationFns = setOf("registerAdbWatchers", "unregisterAdbWatchers")

    private fun enclosingFun(i: Int): String? = (i downTo 0).firstNotNullOfOrNull { j ->
        Regex("""\bfun\s+(\w+)""").find(code(lines[j]))?.groupValues?.get(1)
    }

    @Test
    fun `every ADB-mutating call has a real code guard just before it in its own scope`() {
        val bad = mutableListOf<String>()
        for ((i, raw) in lines.withIndex()) {
            val c = code(raw)
            if (!risky.containsMatchIn(c)) continue
            if (enclosingFun(i) in registrationFns) continue
            if (guard.containsMatchIn(c) || inlineGuard.containsMatchIn(c)) continue
            // The nearest preceding scope start, then at most 4 code lines back from the call.
            val start = (i - 1 downTo 0).firstOrNull { scopeStart.containsMatchIn(code(lines[it])) } ?: 0
            val window = (i - 1 downTo start).map { code(lines[it]) }.filter { it.isNotBlank() }.take(4)
            if (window.none { guard.containsMatchIn(it) }) bad += "${i + 1}: ${raw.trim()}"
        }
        assertTrue("ADB calls without a code guard right before them: $bad", bad.isEmpty())
    }

    @Test
    fun `the registration functions are called only after the mode reconcile`() {
        val calls = lines.withIndex().filter { (i, l) ->
            val c = code(l)
            (c.contains("registerAdbWatchers()") && !c.contains("fun ")) && !c.contains("unregisterAdbWatchers()") ||
                (c.contains("unregisterAdbWatchers()") && !c.contains("fun "))
        }
        // The only callers: the reconcile (guarded by the policy) and onDestroy (never registers).
        val outside = calls.filter { (i, _) -> enclosingFun(i) !in setOf("reconcileAdbWatchers", "onDestroy", "registerAdbWatchers") }
        assertTrue("registration called from outside the reconcile: ${outside.map { it.index + 1 }}", outside.isEmpty())
    }

    @Test
    fun `every ADB-only callback starts with the early return`() {
        val entries = listOf(
            "val usbDebuggingObserver", "val wirelessDebuggingObserver", "val developerOptionsObserver",
            "val wifiJoinCallback", "val screenReceiver", "val approveJoinedWifi",
        )
        val bad = entries.filter { name ->
            val at = lines.indexOfFirst { code(it).contains(name) }
            // The first code line after the callback's entry function (or the Runnable's opening brace).
            val entry = (at until lines.size).first {
                val c = code(lines[it])
                c.contains("override fun onChange") || c.contains("override fun onAvailable") ||
                    c.contains("override fun onReceive") || c.contains("Runnable {")
            }
            val firstCode = (entry + 1 until lines.size).map { code(lines[it]) }.first { it.isNotBlank() }
            !Regex("""if\s*\(\s*!usesAdb\(\)""").containsMatchIn(firstCode)
        }
        assertTrue("callbacks without an early return on !usesAdb(): $bad", bad.isEmpty())
    }
}
