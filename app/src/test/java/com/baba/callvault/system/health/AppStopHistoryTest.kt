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
}
