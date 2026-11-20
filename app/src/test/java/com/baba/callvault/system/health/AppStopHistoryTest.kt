/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.ApplicationExitInfo
import android.app.ApplicationStartInfo
import com.baba.callvault.system.health.AppStopHistory.AppStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStopHistoryTest {

    private val call = 1_000_000L

    @Test
    fun `a call missed while the app was force-stopped is blamed on the force-stop`() {
        // #71: the first start after the call came out of a force-stop, so no call broadcast could reach the app.
        val starts = listOf(AppStart(call - 60_000, afterForceStop = false), AppStart(call + 120_000, afterForceStop = true))
        assertTrue(AppStopHistory.wasForceStoppedDuring(starts, call))
    }

    @Test
    fun `only the first start after the call counts`() {
        // A force-stop that happened later than the first start after the call says nothing about the call.
        val starts = listOf(AppStart(call + 60_000, afterForceStop = false), AppStart(call + 120_000, afterForceStop = true))
        assertFalse(AppStopHistory.wasForceStoppedDuring(starts, call))
    }

    @Test
    fun `no start after the call, or no history, says nothing`() {
        assertFalse(AppStopHistory.wasForceStoppedDuring(listOf(AppStart(call - 1, afterForceStop = true)), call))
        assertFalse(AppStopHistory.wasForceStoppedDuring(emptyList(), call))
    }

    @Test
    fun `reasons are printed by name`() {
        assertEquals("USER_REQUESTED", AppStopHistory.exitReasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("LOW_MEMORY", AppStopHistory.exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("BOOT_COMPLETE", AppStopHistory.startReasonName(ApplicationStartInfo.START_REASON_BOOT_COMPLETE))
        assertEquals("reason 999", AppStopHistory.exitReasonName(999))
    }

    @Test
    fun `starts from an earlier boot are dropped, not given a made-up time`() {
        // Seen on the Android 16 emulator: start times are "since boot", so October starts read as later today.
        val nowWall = 10_000_000L
        val nowSinceBoot = 600_000L // booted 10 minutes ago
        val raw = listOf(
            AppStopHistory.RawStart(sinceBootMillis = 500_000, afterForceStop = true),
            AppStopHistory.RawStart(sinceBootMillis = 480_000, afterForceStop = false),
            AppStopHistory.RawStart(sinceBootMillis = 3_000_000, afterForceStop = true), // earlier boot: "in the future"
            AppStopHistory.RawStart(sinceBootMillis = 100_000, afterForceStop = false),
        )
        val starts = AppStopHistory.currentBootStarts(raw, nowSinceBootMillis = nowSinceBoot, nowWallMillis = nowWall)
        assertEquals(listOf(AppStart(9_900_000, true), AppStart(9_880_000, false)), starts)
    }

    @Test
    fun `a jump forward in time ends the current boot even when it is not in the future`() {
        val raw = listOf(
            AppStopHistory.RawStart(sinceBootMillis = 500_000, afterForceStop = false),
            AppStopHistory.RawStart(sinceBootMillis = 590_000 + 0, afterForceStop = true), // 90 s newer than the newest: another boot
        )
        assertEquals(1, AppStopHistory.currentBootStarts(raw, nowSinceBootMillis = 600_000, nowWallMillis = 10_000_000L).size)
    }

    @Test
    fun `small reorderings inside one boot are kept`() {
        // The emulator listed BROADCAST starts 40 s out of order within the same boot.
        val raw = listOf(
            AppStopHistory.RawStart(sinceBootMillis = 54_000, afterForceStop = false),
            AppStopHistory.RawStart(sinceBootMillis = 94_000, afterForceStop = false),
            AppStopHistory.RawStart(sinceBootMillis = 36_000, afterForceStop = false),
        )
        assertEquals(3, AppStopHistory.currentBootStarts(raw, nowSinceBootMillis = 600_000, nowWallMillis = 10_000_000L).size)
    }

    // ---- What a crash or an ANR said (the vivo report showed only "crash") ----

    private val anrTrace = """
        |----- pid 13572 at 2026-11-20 17:48:40.000 -----
        |Cmd line: com.baba.callvault
        |Build fingerprint: 'vivo/PD2546/PD2546:17/CP2A/1:user/release-keys'
        |
        |DALVIK THREADS (40):
        |"Signal Catcher" daemon prio=10 tid=5 Runnable
        |  native: #00 pc 000 /apex/libart.so
        |
        |"main" prio=5 tid=1 Blocked
        |  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x7 self=0x8
        |  at com.baba.callvault.server.RecorderConnection.await(RecorderConnection.kt:120)
        |  - waiting to lock <0x0a> held by thread 22
        |  at com.baba.callvault.MainActivity.onResume(MainActivity.kt:80)
        |
        |"binder:13572_1" prio=5 tid=9 Native
    """.trimMargin()

    @Test
    fun `an ANR excerpt is the main thread's stack, not the dump's preamble`() {
        val lines = AppStopHistory.traceExcerpt(anrTrace, maxLines = 6)
        assertEquals("\"main\" prio=5 tid=1 Blocked", lines.first())
        assertTrue(lines.any { it.contains("RecorderConnection.await") })
        assertTrue(lines.none { it.contains("Signal Catcher") })
        assertTrue(lines.none { it.contains("binder:13572_1") })
        assertTrue(lines.size <= 6)
    }

    @Test
    fun `a trace without a main thread falls back to its first lines`() {
        val lines = AppStopHistory.traceExcerpt("a\nb\n\nc\nd", maxLines = 2)
        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun `the exit line carries the pid, so a crash note can be matched to it`() {
        val line = AppStopHistory.exitLine(
            time = "2026-11-20 17:48:48", process = "com.baba.callvault", reason = ApplicationExitInfo.REASON_CRASH,
            importance = 125, pid = 13572, status = 0, description = "crash",
        )
        assertEquals("exit  2026-11-20 17:48:48 com.baba.callvault CRASH pid=13572 importance=125 \"crash\"", line)
    }

    @Test
    fun `a signalled exit says which signal`() {
        val line = AppStopHistory.exitLine(
            "2026-11-20 17:48:48", "com.baba.callvault", ApplicationExitInfo.REASON_SIGNALED, 400, 9, 9, null,
        )
        assertTrue(line, line.contains("status=9"))
    }
}
