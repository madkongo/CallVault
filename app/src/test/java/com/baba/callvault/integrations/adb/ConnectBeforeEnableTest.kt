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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C115 / GitHub #40 (Pixel 7, Android 17): `adb_wifi_enabled` reads 0 to apps although Wireless debugging is
 * on, and the write that would "fix" it needs a grant that is only given after the first connect. So a 0
 * read must not end the attempt: look for a connect port first, without writing anything.
 */
class ConnectBeforeEnableTest {

    @Test
    fun `loopback is tried first, then the address mDNS resolved`() {
        assertEquals(listOf("127.0.0.1", "192.168.1.20"), ConnectBeforeEnable.hostsToTry("192.168.1.20"))
    }

    @Test
    fun `no resolved address, or one that is loopback already, leaves just loopback`() {
        assertEquals(listOf("127.0.0.1"), ConnectBeforeEnable.hostsToTry(null))
        assertEquals(listOf("127.0.0.1"), ConnectBeforeEnable.hostsToTry(""))
        assertEquals(listOf("127.0.0.1"), ConnectBeforeEnable.hostsToTry("127.0.0.1"))
    }

    @Test
    fun `a stopped adbd or no wifi cannot answer, so the quick look is skipped`() {
        assertFalse(ConnectBeforeEnable.shouldLook(AdbdState.STOPPED, WifiState.CONNECTED))
        assertFalse(ConnectBeforeEnable.shouldLook(AdbdState.RUNNING, WifiState.NOT_CONNECTED))
        assertTrue(ConnectBeforeEnable.shouldLook(AdbdState.RUNNING, WifiState.CONNECTED))
        assertTrue(ConnectBeforeEnable.shouldLook(AdbdState.UNKNOWN, WifiState.UNKNOWN))
    }

    // --- behaviour: the hosts and the one shared budget (M3) ---

    private class Clock(var now: Long = 0)

    @Test
    fun `the first host that connects wins and the second is never dialled`() {
        val dialled = mutableListOf<String>()
        val got = ConnectBeforeEnable.tryHosts("10.0.0.5", 40123, 8_000, { 0 }, { h, _ -> dialled += h; h == "127.0.0.1" }) {}
        assertEquals("127.0.0.1", got)
        assertEquals(listOf("127.0.0.1"), dialled)
    }

    @Test
    fun `a refused loopback falls back to the resolved address`() {
        val got = ConnectBeforeEnable.tryHosts("10.0.0.5", 40123, 8_000, { 0 }, { h, _ -> h == "10.0.0.5" }) {}
        assertEquals("10.0.0.5", got)
    }

    @Test
    fun `both hosts share one budget, the second gets only what the first left`() {
        val clock = Clock()
        val budgets = mutableListOf<Long>()
        val got = ConnectBeforeEnable.tryHosts("10.0.0.5", 1, 8_000, { clock.now }, { _, budget -> budgets += budget; clock.now += 3_000; false }) {}
        assertNull(got)
        assertEquals(listOf(8_000L, 5_000L), budgets)
    }

    @Test
    fun `an exhausted budget skips the second host and says so`() {
        val clock = Clock()
        val dialled = mutableListOf<String>()
        val log = mutableListOf<String>()
        val got = ConnectBeforeEnable.tryHosts("10.0.0.5", 1, 8_000, { clock.now }, { h, _ -> dialled += h; clock.now += 8_000; false }) { log += it }
        assertNull(got)
        assertEquals(listOf("127.0.0.1"), dialled)
        assertTrue(log.toString(), log.any { it.contains("budget") })
    }

    @Test
    fun `the whole look stays far below the connect lock's entry wait`() {
        assertTrue(ConnectBeforeEnable.LOOK_MDNS_MS + ConnectBeforeEnable.CONNECT_BUDGET_MS + 1_000 < 20_000)
    }

    // --- wiring (source-level, like SettleVerdictWiringTest) ---

    private val src: String = listOf(
        "src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
        "app/src/main/java/com/baba/callvault/integrations/adb/AdbShell.kt",
    ).map(::File).first { it.exists() }.readText()

    private fun body(startMarker: String, endMarker: String): String {
        val a = src.indexOf(startMarker)
        assertTrue("missing $startMarker", a >= 0)
        val b = src.indexOf(endMarker, a + startMarker.length)
        assertTrue("missing $endMarker", b > a)
        return src.substring(a, b)
    }

    @Test
    fun `the Wireless-debugging connect looks for a port before it tries to enable the switch`() {
        val b = body("private fun connectViaWirelessDebuggingLocked", "// Last good port")
        val look = b.indexOf("connectWithoutEnabling(")
        val enable = b.indexOf("enableWirelessDebugging(context)")
        assertTrue("no quick look", look >= 0)
        assertTrue("the look must come first", enable > look)
        // and only when the read says off or absent
        assertTrue(b.substring(0, enable).contains("!isWirelessDebuggingEnabled(context)"))
    }

    @Test
    fun `the quick look never writes the switch or takes ownership of it`() {
        val b = body("private fun connectWithoutEnabling(", "\n    /**")
        assertTrue("find and the connect must be wrapped so an exception falls through to the enable rules", b.contains("runCatching"))
        listOf("enableWirelessDebugging(", "putInt(", "markOwnWirelessDebuggingWrite", "setWirelessDebuggingEnabledByUs", "setWirelessDebuggingTurnedOffByUser")
            .forEach { assertFalse("the look must not call $it", b.contains(it)) }
        assertTrue("it must say what it tried", b.contains("AppLogger"))
    }
}
