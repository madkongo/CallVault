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
}
