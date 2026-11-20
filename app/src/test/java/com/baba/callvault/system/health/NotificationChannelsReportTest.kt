/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.system.health.NotificationChannelsReport.Channel
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review N22 follow-up: a missing or blocked channel crashed (or silenced) every app call on a vivo, and nothing in a
 * debug report said which channels existed. The report now lists them, read when the report is built.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationChannelsReportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `each channel shows its importance, whether it is blocked, and its group`() {
        val lines = NotificationChannelsReport.lines(
            appNotificationsEnabled = true,
            channels = listOf(
                Channel("recorder_keepalive", NotificationManager.IMPORTANCE_MIN, group = null),
                Channel("recording_channel_service", NotificationManager.IMPORTANCE_NONE, group = "recording_channel_group"),
            ),
            expected = emptyList(),
        )
        assertEquals(
            listOf(
                "App notifications enabled: yes",
                "recorder_keepalive: importance MIN, blocked no, group -",
                "recording_channel_service: importance NONE, blocked yes, group recording_channel_group",
            ),
            lines,
        )
    }

    @Test
    fun `an expected channel that does not exist is named as missing`() {
        val lines = NotificationChannelsReport.lines(
            appNotificationsEnabled = false,
            channels = listOf(Channel("recorder_keepalive", NotificationManager.IMPORTANCE_LOW, group = null)),
            expected = listOf("recorder_keepalive", "recording_channel_service"),
        )
        assertEquals("App notifications enabled: no", lines.first())
        assertTrue(lines.contains("recording_channel_service: MISSING"))
        assertTrue(lines.none { it == "recorder_keepalive: MISSING" })
    }

    @Test
    fun `an unknown permission state is said as unknown`() {
        val lines = NotificationChannelsReport.lines(appNotificationsEnabled = null, channels = emptyList(), expected = emptyList())
        assertEquals(listOf("App notifications enabled: ?", "no channels"), lines)
    }

    @Test
    fun `the report reads the phone's channels`() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("probe_channel", "Probe", NotificationManager.IMPORTANCE_DEFAULT))

        val lines = NotificationChannelsReport.reportLines(app)

        assertTrue(lines.first().startsWith("App notifications enabled: "))
        assertTrue(lines.toString(), lines.contains("probe_channel: importance DEFAULT, blocked no, group -"))
    }

    @Test
    fun `the debug report carries the channels block, with debug logging off`() {
        AppLogger.setRingEnabled(false)

        val report = runBlocking { AppLogger.buildShareableReport(app) }

        assertNotNull(report)
        val text = report!!.readText()
        assertTrue(text.contains("--- Notification channels ---"))
        assertTrue(text.contains("App notifications enabled: "))
    }
}
