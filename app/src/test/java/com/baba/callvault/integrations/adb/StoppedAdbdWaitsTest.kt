/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GH89-4 / C65: no waiting for a listener or an mDNS service that a stopped adbd cannot provide. */
class StoppedAdbdWaitsTest {

    @Test
    fun `one stopped reading is a restart in progress, only a persistent one is given up on (M1)`() {
        assertFalse(StoppedAdbdWaits.persistentlyStopped(AdbdState.STOPPED, 0))
        assertFalse(StoppedAdbdWaits.persistentlyStopped(AdbdState.STOPPED, StoppedAdbdWaits.GIVE_UP_AFTER_MS - 1))
        assertTrue(StoppedAdbdWaits.persistentlyStopped(AdbdState.STOPPED, StoppedAdbdWaits.GIVE_UP_AFTER_MS))
        assertFalse(StoppedAdbdWaits.persistentlyStopped(AdbdState.RUNNING, 60_000))
        assertFalse(StoppedAdbdWaits.persistentlyStopped(AdbdState.UNKNOWN, 60_000))
    }

    private fun source(vararg rel: String): String =
        rel.flatMap { listOf("src/main/java/com/baba/callvault/$it", "app/src/main/java/com/baba/callvault/$it") }
            .map(::File).first { it.exists() }.readText()

    @Test
    fun `the loopback self-heal wait asks the gate`() {
        val s = source("integrations/adb/AdbShell.kt")
        val a = s.indexOf("val deadline = startedAt + LOOPBACK_SELFHEAL_MS")
        assertTrue(a > 0)
        // the wait loop itself polls adbd and gives up only on a persistent stop
        assertTrue(s.substring(a, a + 1500).contains("adbdPersistentlyStopped()"))
    }

    @Test
    fun `the launcher takes the revival decision instead of re-arming through mDNS while adbd is stopped`() {
        val s = source("server/RecorderServerLauncher.kt")
        val a = s.indexOf("offline mode but no connection")
        assertTrue(a > 0)
        val call = s.lastIndexOf("reviveAdbdIfStopped(", a)
        assertTrue(call > 0)
        val window = s.substring(maxOf(0, call - 900), a + 900)
        assertTrue(window.contains("adbdPersistentlyStopped()"))
        assertTrue(window.contains("reviveAdbdIfStopped("))
    }

    @Test
    fun `the connect port search returns for a stopped adbd before it waits on mDNS`() {
        val s = source("integrations/adb/ConnectPortFinder.kt")
        val stopped = s.indexOf("StoppedAdbdWaits.persistentlyStopped(")
        val mdns = s.indexOf("AdbMdns.discoverEndpoint(")
        assertTrue("stopped check missing", stopped > 0)
        assertTrue("it must come before the mDNS wait (C65)", stopped < mdns)
    }
}
