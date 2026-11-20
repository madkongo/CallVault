/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.R
import com.baba.callvault.services.recording.SharedStatusNotice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Review N22 follow-up: the post-boot call monitor starts with the recorded call's notice when one holds the shared
 * id. On a missing channel that notice would crash the app from startForeground; the monitor starts with its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallMonitorForegroundNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = SharedStatusNotice.resetForTest()

    @After
    fun tearDown() = SharedStatusNotice.resetForTest()

    private fun startMonitor(): CallMonitorService {
        val controller = Robolectric.buildService(CallMonitorService::class.java).create()
        controller.startCommand(0, 1)
        return controller.get()
    }

    @Test
    fun `a held notice on a missing channel never reaches startForeground`() {
        SharedStatusNotice.claim(
            SharedStatusNotice.Owner.VOIP,
            NotificationCompat.Builder(app, "no_such_channel").setSmallIcon(R.drawable.ic_mic).build(),
        )

        val service = startMonitor()

        val posted = shadowOf(service).lastForegroundNotification
        assertNotNull("startForeground is still called", posted)
        assertEquals("recorder_keepalive", posted.channelId)
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel("recorder_keepalive"))
    }

    @Test
    fun `its own channel is created again if it was deleted while the service lived`() {
        val controller = Robolectric.buildService(CallMonitorService::class.java).create()
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("recorder_keepalive")

        controller.startCommand(0, 1)

        assertEquals("recorder_keepalive", shadowOf(controller.get()).lastForegroundNotification.channelId)
        assertNotNull(manager.getNotificationChannel("recorder_keepalive"))
    }
}
