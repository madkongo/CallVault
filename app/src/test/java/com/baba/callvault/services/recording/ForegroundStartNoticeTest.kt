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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review N22 follow-up: the keep-alive and the call monitor start with the recorded call's notice when one holds the
 * shared id (SharedStatusNotice.contentForKeepAlive). That notice belongs to another channel, so it could reach
 * startForeground on a missing channel and crash the app. startForeground must still be called, so the service falls
 * back to its own notice, on a channel it has just made sure of.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundStartNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun manager() = app.getSystemService(NotificationManager::class.java)
    private fun notice(channel: String) = NotificationCompat.Builder(app, channel).setSmallIcon(R.drawable.ic_mic).build()

    @Before
    fun setUp() {
        SharedStatusNotice.resetForTest()
    }

    @After
    fun tearDown() {
        SharedStatusNotice.resetForTest()
    }

    // --- the pure decision ---

    @Test
    fun `with no recorded call the service starts with its own notice`() {
        assertEquals("own", ForegroundNoticeChannel.chooseForStart(held = null, own = "own") { true })
    }

    @Test
    fun `a recorded call's notice on an existing channel is kept`() {
        assertEquals("held", ForegroundNoticeChannel.chooseForStart(held = "held", own = "own") { true })
    }

    @Test
    fun `a recorded call's notice on a missing channel gives way to the service's own`() {
        assertEquals("own", ForegroundNoticeChannel.chooseForStart(held = "held", own = "own") { false })
    }

    @Test
    fun `a failing channel lookup gives way to the service's own notice`() {
        assertEquals("own", ForegroundNoticeChannel.chooseForStart(held = "held", own = "own") { error("binder") })
    }

    // --- with Android's notification manager ---

    @Test
    fun `a held notice on a channel that cannot be created is replaced by the own notice, whose channel is ensured`() {
        SharedStatusNotice.claim(SharedStatusNotice.Owner.VOIP, notice("no_such_channel"))
        val own = notice("own_channel")
        var ensured = false

        val chosen = ForegroundNoticeChannel.noticeForStart(app, own) { ensured = true }

        assertSame(own, chosen)
        assertTrue("the own channel is made sure of before startForeground", ensured)
    }

    @Test
    fun `a held notice on an existing channel is what the service starts with`() {
        RecordingNotificationHelper.ensureChannels(app, force = true)
        val held = notice(RecordingNotificationHelper.CHANNEL_ID_SERVICE)
        SharedStatusNotice.claim(SharedStatusNotice.Owner.VOIP, held)
        var ensured = false

        val chosen = ForegroundNoticeChannel.noticeForStart(app, notice("own_channel")) { ensured = true }

        assertSame(held, chosen)
        assertFalse("the own channel is not touched while a call holds the id", ensured)
    }

    @Test
    fun `a held notice whose recording channel was deleted is posted after the channel is created again`() {
        RecordingNotificationHelper.ensureChannels(app, force = true)
        manager().deleteNotificationChannel(RecordingNotificationHelper.CHANNEL_ID_SERVICE)
        val held = notice(RecordingNotificationHelper.CHANNEL_ID_SERVICE)
        SharedStatusNotice.claim(SharedStatusNotice.Owner.CARRIER, held)

        assertSame(held, ForegroundNoticeChannel.noticeForStart(app, notice("own_channel")) {})
    }
}
