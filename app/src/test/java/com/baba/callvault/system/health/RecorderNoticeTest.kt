/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.data.RecordingState
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuLifecycleWatcher
import com.baba.callvault.services.recording.RecordingSwitch
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Review batch B2: when "CallVault cannot record" is posted and cleared (C61, C68) and which channel notices use (R31). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(app)
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    private companion object {
        const val ID_RECORDER_UNAVAILABLE = 4716
        const val ID_SHIZUKU_STOPPED = 4718
    }

    private fun posted(id: Int) = shadowOf(manager).getNotification(id)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- C61 ----

    @Test
    fun `no cannot-record warning is posted while the user has recording switched off`() {
        prefs.setRecordingState(RecordingState.STOPPED)

        SilentFailureNotifier.warnRecorderUnavailable(app)

        assertNull(posted(ID_RECORDER_UNAVAILABLE))
    }

    @Test
    fun `the cannot-record warning is still posted while recording is on`() {
        prefs.setRecordingState(RecordingState.ACTIVE)

        SilentFailureNotifier.warnRecorderUnavailable(app)

        assertNotNull(posted(ID_RECORDER_UNAVAILABLE))
    }

    @Test
    fun `switching recording off takes down a warning posted before the stop`() {
        prefs.setRecordingState(RecordingState.ACTIVE)
        SilentFailureNotifier.warnRecorderUnavailable(app)
        assertNotNull(posted(ID_RECORDER_UNAVAILABLE))

        RecordingSwitch.apply(app, RecordingState.STOPPED)

        assertNull(posted(ID_RECORDER_UNAVAILABLE))
    }

    // ---- C68 ----

    private fun shizukuReturned(ensureRunningAnswer: Boolean) {
        prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
        prefs.setRecordingState(RecordingState.ACTIVE)
        SilentFailureNotifier.warnRecorderUnavailable(app)
        val asked = CountDownLatch(1)
        mockkObject(RecorderBackend)
        every { RecorderBackend.ensureRunning(any(), any()) } answers { asked.countDown(); ensureRunningAnswer }
        ShizukuLifecycleWatcher::class.java.getDeclaredMethod("onReceived", android.content.Context::class.java)
            .apply { isAccessible = true }
            .invoke(ShizukuLifecycleWatcher, app)
        assertTrue("the recorder was never asked for", asked.await(5, TimeUnit.SECONDS))
        // The decision follows the answer on the same worker thread.
        Thread.sleep(300)
    }

    @Test
    fun `Shizuku coming back does not clear the warning when no recorder binds`() {
        shizukuReturned(ensureRunningAnswer = false)

        assertNotNull("the user must still be told nothing is recording", posted(ID_RECORDER_UNAVAILABLE))
    }

    @Test
    fun `Shizuku coming back clears the warning once a recorder binds`() {
        shizukuReturned(ensureRunningAnswer = true)

        assertNull(posted(ID_RECORDER_UNAVAILABLE))
    }

    // ---- R31 ----

    @Test
    fun `good news goes to the quiet notices channel, a warning stays on the loud one`() {
        SilentFailureNotifier.noteShizukuRestarted(app)
        prefs.setRecordingState(RecordingState.ACTIVE)
        SilentFailureNotifier.warnRecorderUnavailable(app)

        val info = posted(ID_SHIZUKU_STOPPED)!!
        val warning = posted(ID_RECORDER_UNAVAILABLE)!!
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(info.channelId).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(warning.channelId).importance)
    }
}
