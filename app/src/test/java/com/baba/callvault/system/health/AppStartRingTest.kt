/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.system.health.AppStopHistory.ExitRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C141: below Android 15 the app's own start list says whether it was running again after a stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppStartRingTest {
    private val hour = 60L * 60 * 1000
    private val call = 100 * hour
    private val stop = ExitRecord(call - 5 * hour, ApplicationExitInfo.REASON_USER_REQUESTED, "[STOP APP] fully stop x by user request")

    @Test
    fun `starts are recorded newest first and capped`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        (1..AppStartRing.CAPACITY + 4).forEach { AppStartRing.record(context, it * 1000L) }
        val starts = AppStartRing.starts(context)
        assertEquals(AppStartRing.CAPACITY, starts.size)
        assertEquals((AppStartRing.CAPACITY + 4) * 1000L, starts.first().atWallMillis)
    }

    @Test
    fun `a start between the stop and the call clears the stop's blame`() {
        val backUp = AppStopHistory.AppStart(call - 2 * hour, afterForceStop = false)
        assertFalse(AppStopHistory.wasStoppedByUserDuring(listOf(stop), listOf(backUp), call))
    }

    @Test
    fun `an empty ring keeps the old day-long reach`() {
        assertTrue(AppStopHistory.wasStoppedByUserDuring(listOf(stop), AppStartRing.starts(ApplicationProvider.getApplicationContext()), call))
    }
}
