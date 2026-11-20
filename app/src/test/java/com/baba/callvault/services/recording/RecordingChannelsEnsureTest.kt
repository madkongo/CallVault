/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review N22 follow-up: every app-call notice update (Mark, Pause) asked for the recording channels again, two binder
 * calls each time. They are now created once per process, and again when a foreground post finds one missing (a user
 * or an OEM can delete a channel while the app runs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingChannelsEnsureTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun manager() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun freshProcess() {
        RecordingNotificationHelper.resetEnsuredForTest()
        manager().deleteNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_SERVICE)
        manager().deleteNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_ERROR)
    }

    @Test
    fun `the channels are created on first use`() {
        RecordingNotificationHelper.ensureChannels(app)
        assertNotNull(manager().getNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_SERVICE))
        assertNotNull(manager().getNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_ERROR))
    }

    @Test
    fun `later notice updates do not ask for the channels again`() {
        RecordingNotificationHelper.ensureChannels(app)
        manager().deleteNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_SERVICE)

        RecordingNotificationHelper.ensureChannels(app)

        assertNull("cached: no second creation", manager().getNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_SERVICE))
    }

    @Test
    fun `a channel deleted after it was ensured is created again before a foreground post`() {
        RecordingNotificationHelper.ensureChannels(app)
        manager().deleteNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_SERVICE)
        val notice = NotificationCompat.Builder(app, RecordingNotificationHelper.CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_mic).build()

        assertTrue(ForegroundNoticeChannel.usable(app, notice))
        assertNotNull(manager().getNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_SERVICE))
    }
}
