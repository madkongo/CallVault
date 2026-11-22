/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.ApplicationExitInfo
import com.baba.callvault.system.health.AppStopHistory.AppStart
import com.baba.callvault.system.health.AppStopHistory.ExitRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 115: reading the user's own stop out of the system's exit record. */
class UserStopNoticeTest {

    private val now = 100L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000

    // The two descriptions measured on an Android 16 emulator.
    private val stopApp = ExitRecord(now - hour, ApplicationExitInfo.REASON_USER_REQUESTED, "[STOP APP] fully stop com.baba.callvault/0 by user request")
    private val forceStop = ExitRecord(now - hour, ApplicationExitInfo.REASON_USER_REQUESTED, "[FORCE STOP] stop com.baba.callvault due to from pid 3887")

    @Test
    fun `Active apps Stop and Force stop are user stops`() {
        assertTrue(AppStopHistory.isUserStop(stopApp))
        assertTrue(AppStopHistory.isUserStop(forceStop))
    }

    @Test
    fun `other ways the process ends are not user stops`() {
        val lowMemory = ExitRecord(now, ApplicationExitInfo.REASON_LOW_MEMORY, "kill background")
        val crash = ExitRecord(now, ApplicationExitInfo.REASON_CRASH, null)
        val swipedAway = ExitRecord(now, ApplicationExitInfo.REASON_USER_REQUESTED, "remove task")
        val noDescription = ExitRecord(now, ApplicationExitInfo.REASON_USER_REQUESTED, null)
        listOf(lowMemory, crash, swipedAway, noDescription).forEach { assertFalse(it.toString(), AppStopHistory.isUserStop(it)) }
    }

    @Test
    fun `the newest user stop is the one that counts`() {
        val older = stopApp.copy(atMillis = now - 5 * hour)
        val crash = ExitRecord(now - 30 * 60 * 1000, ApplicationExitInfo.REASON_CRASH, null)
        assertEquals(stopApp, AppStopHistory.latestUserStop(listOf(crash, stopApp, older)))
        assertNull(AppStopHistory.latestUserStop(listOf(crash)))
    }

    @Test
    fun `a stop is announced once`() {
        assertEquals(stopApp, UserStopNotice.stopToNotify(listOf(stopApp), notifiedAtMillis = 0, nowMillis = now))
        assertNull(UserStopNotice.stopToNotify(listOf(stopApp), notifiedAtMillis = stopApp.atMillis, nowMillis = now))
    }

    @Test
    fun `a newer stop is announced again`() {
        val newer = forceStop.copy(atMillis = now - 60_000)
        assertEquals(newer, UserStopNotice.stopToNotify(listOf(newer, stopApp), notifiedAtMillis = stopApp.atMillis, nowMillis = now))
    }

    @Test
    fun `an old stop is history, not news`() {
        val old = stopApp.copy(atMillis = now - UserStopNotice.MAX_AGE_MILLIS - 1)
        assertNull(UserStopNotice.stopToNotify(listOf(old), notifiedAtMillis = 0, nowMillis = now))
        assertNull(UserStopNotice.stopForCard(listOf(old), dismissedAtMillis = 0, nowMillis = now))
    }

    @Test
    fun `the card shows until it is closed, and again for a later stop`() {
        assertEquals(stopApp, UserStopNotice.stopForCard(listOf(stopApp), dismissedAtMillis = 0, nowMillis = now))
        assertNull(UserStopNotice.stopForCard(listOf(stopApp), dismissedAtMillis = stopApp.atMillis, nowMillis = now))
        val newer = stopApp.copy(atMillis = now - 1_000)
        assertEquals(newer, UserStopNotice.stopForCard(listOf(newer, stopApp), dismissedAtMillis = stopApp.atMillis, nowMillis = now))
    }

    // wasStoppedByUserDuring: the "call not recorded" line used to miss the Stop button (it read only the force-stop start flag).

    private val call = now

    @Test
    fun `a call after a user stop with nothing in between was lost to the stop`() {
        assertTrue(AppStopHistory.wasStoppedByUserDuring(listOf(stopApp), emptyList(), call))
    }

    @Test
    fun `a stop after the call says nothing about it`() {
        val later = stopApp.copy(atMillis = call + 1_000)
        assertFalse(AppStopHistory.wasStoppedByUserDuring(listOf(later), emptyList(), call))
    }

    @Test
    fun `another exit between the stop and the call means the app was back`() {
        val crash = ExitRecord(call - 30 * 60 * 1000, ApplicationExitInfo.REASON_CRASH, null)
        assertFalse(AppStopHistory.wasStoppedByUserDuring(listOf(crash, stopApp), emptyList(), call))
    }

    @Test
    fun `without start history a stop more than a day old is not blamed`() {
        val old = stopApp.copy(atMillis = call - 25 * hour)
        assertFalse(AppStopHistory.wasStoppedByUserDuring(listOf(old), emptyList(), call))
    }

    @Test
    fun `with start history, a start well after the stop and before the call means it was running`() {
        val backUp = AppStart(atWallMillis = stopApp.atMillis + 20 * 60 * 1000, afterForceStop = false)
        assertFalse(AppStopHistory.wasStoppedByUserDuring(listOf(stopApp), listOf(backUp), call))
    }

    @Test
    fun `with start history, the call's own wake does not count as the app being back`() {
        val wake = AppStart(atWallMillis = call - 2_000, afterForceStop = false)
        assertTrue(AppStopHistory.wasStoppedByUserDuring(listOf(stopApp), listOf(wake), call))
    }

    // C142: Android 11-13 records the installer's kill during an update as USER_REQUESTED "stop ... installPackageLI".
    private val installKill = ExitRecord(now - hour, ApplicationExitInfo.REASON_USER_REQUESTED, "stop com.baba.callvault due to installPackageLI")

    @Test
    fun `a stop recorded at the moment of an update is the installer's, not the user's`() {
        val kept = AppStopHistory.withoutInstallKills(listOf(installKill), lastUpdateMillis = installKill.atMillis + 5_000)
        assertNull(AppStopHistory.latestUserStop(kept))
    }

    @Test
    fun `a stop an hour after the update still counts`() {
        val kept = AppStopHistory.withoutInstallKills(listOf(stopApp), lastUpdateMillis = stopApp.atMillis - hour)
        assertEquals(stopApp, AppStopHistory.latestUserStop(kept))
    }

    @Test
    fun `an unknown install time filters nothing`() {
        assertEquals(listOf(installKill), AppStopHistory.withoutInstallKills(listOf(installKill), lastUpdateMillis = 0))
    }

    @Test
    fun `a stop that fell inside a recorded session did not lose that call`() {
        val stop = stopApp.copy(atMillis = 5_000L)
        assertTrue(UserStopNotice.stopDuringRecording(listOf(stop), sessionStartedAt = 1_000L, sessionEndedAt = 9_000L) == stop)
    }

    @Test
    fun `a stop outside any recorded session is still announced`() {
        val stop = stopApp.copy(atMillis = 20_000L)
        assertNull(UserStopNotice.stopDuringRecording(listOf(stop), sessionStartedAt = 1_000L, sessionEndedAt = 9_000L))
    }

    @Test
    fun `an unknown session start never hides a stop`() {
        assertNull(UserStopNotice.stopDuringRecording(listOf(stopApp.copy(atMillis = 5_000L)), sessionStartedAt = null, sessionEndedAt = 9_000L))
    }
}
