/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review R11: the keep-alive re-posts its notice only when what it would show differs from what is posted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeKeyTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun notice(title: String, text: String, big: String? = null, action: String? = null, channel: String = "recorder_keepalive"): Notification {
        val b = NotificationCompat.Builder(app, channel).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(text).setOngoing(true)
        if (big != null) b.setStyle(NotificationCompat.BigTextStyle().bigText(big))
        if (action != null) b.addAction(0, action, null)
        return b.build()
    }

    @Test
    fun `the same content builds the same key`() {
        assertEquals(NoticeKey.of(notice("Down", "Starting")), NoticeKey.of(notice("Down", "Starting")))
    }

    @Test
    fun `a different text, title, big text, action or channel changes the key`() {
        val base = NoticeKey.of(notice("Down", "Starting", big = "b", action = "Turn on"))
        assertNotEquals(base, NoticeKey.of(notice("Down", "Other", big = "b", action = "Turn on")))
        assertNotEquals(base, NoticeKey.of(notice("Up", "Starting", big = "b", action = "Turn on")))
        assertNotEquals(base, NoticeKey.of(notice("Down", "Starting", big = "c", action = "Turn on")))
        assertNotEquals(base, NoticeKey.of(notice("Down", "Starting", big = "b", action = null)))
        assertNotEquals(base, NoticeKey.of(notice("Down", "Starting", big = "b", action = "Turn on", channel = "recording")))
    }

    @Test
    fun `a recording notice that replaced ours is not mistaken for ours`() {
        assertNotEquals(NoticeKey.of(notice("Ready", "x")), NoticeKey.of(notice("Recording", "Call in progress", channel = "recording")))
    }

    @Test
    fun `a missing posted notice always means post`() {
        assertEquals(true, NoticeKey.shouldPost(posted = null, next = notice("a", "b")))
    }

    @Test
    fun `an identical posted notice means skip, a different one means post`() {
        assertEquals(false, NoticeKey.shouldPost(posted = notice("a", "b"), next = notice("a", "b")))
        assertEquals(true, NoticeKey.shouldPost(posted = notice("a", "b"), next = notice("a", "c")))
    }
}
