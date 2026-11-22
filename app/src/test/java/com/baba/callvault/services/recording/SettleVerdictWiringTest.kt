/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** C99 review: the settle runnable is wired to every place that must cancel it (source-level, like KeepAliveAdbGuardTest). */
class SettleVerdictWiringTest {
    private val src: String = listOf(
        "src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt",
        "app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt",
    ).map(::File).first { it.exists() }.readText()

    private fun body(startMarker: String): String {
        val a = src.indexOf(startMarker)
        assertTrue("missing $startMarker", a >= 0)
        return src.substring(a, minOf(src.length, a + 2500))
    }

    @Test
    fun `unregistering the watchers cancels the pending verdict and the pending flag`() {
        val b = body("private fun unregisterAdbWatchers()")
        assertTrue(b.contains("removeCallbacks(settleWirelessDebuggingOffCause)"))
        assertTrue(b.contains("AdbShell.userOffPending.clear()"))
    }

    @Test
    fun `switching on cancels the pending verdict and the pending flag`() {
        val b = body("if (on) {")
        assertTrue(b.contains("removeCallbacks(settleWirelessDebuggingOffCause)"))
        assertTrue(b.contains("AdbShell.userOffPending.clear()"))
    }

    @Test
    fun `the USER branch starts the pending flag with a fresh Wi-Fi read`() {
        val b = body("WirelessDebuggingOffCause.USER -> {")
        assertTrue(b.contains("AdbShell.userOffPending.start("))
        assertTrue(b.contains("pendingOffWifiIdentity = WifiIdentity.of(applicationContext)"))
    }

    @Test
    fun `the runnable bails out when the mode no longer uses ADB and clears the flag when done`() {
        val b = body("private val settleWirelessDebuggingOffCause = Runnable {")
        assertTrue(b.contains("!usesAdb()"))
        assertTrue(b.contains("AdbShell.userOffPending.clear()"))
    }
}
