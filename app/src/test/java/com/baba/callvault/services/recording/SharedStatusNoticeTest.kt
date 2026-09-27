/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One notification in the shade while a call is recorded, instead of the recording notification sitting
 * next to "Ready to record calls" (maintainer, issue #31 follow-up).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class SharedStatusNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun notification(title: String): Notification =
        NotificationCompat.Builder(context, "test").setContentTitle(title).build()

    @After
    fun reset() = SharedStatusNotice.resetForTest()

    @Test
    fun the_keep_alive_shows_its_own_content_when_no_call_is_recorded() {
        val ready = notification("Ready to record calls")

        assertFalse(SharedStatusNotice.isHeldByRecording)
        assertSame(ready, SharedStatusNotice.contentForKeepAlive(ready))
    }

    /**
     * The keep-alive must post *something* when it starts, and while a call is recorded that something must be
     * the recording notification — posting "Ready" would replace Pause, Mark and Stop, the only in-call controls.
     */
    @Test
    fun while_a_call_is_recorded_the_keep_alive_shows_the_recording_notification() {
        val recording = notification("Recording call")
        SharedStatusNotice.claim(recording)

        assertTrue(SharedStatusNotice.isHeldByRecording)
        assertSame(recording, SharedStatusNotice.contentForKeepAlive(notification("Ready to record calls")))
    }

    @Test
    fun each_update_of_the_recording_notification_replaces_the_last() {
        SharedStatusNotice.claim(notification("Preparing to record"))
        val active = notification("Recording call")
        SharedStatusNotice.claim(active)

        assertSame(active, SharedStatusNotice.contentForKeepAlive(notification("Ready to record calls")))
    }

    /**
     * Android does not cancel a notification id another foreground service still holds, so when the recording
     * lets go the keep-alive has to put "Ready" back — or "Recording call" stays up after the call.
     */
    @Test
    fun releasing_tells_the_keep_alive_to_put_its_own_content_back() {
        var told = 0
        SharedStatusNotice.onReleased = { told++ }
        SharedStatusNotice.claim(notification("Recording call"))

        SharedStatusNotice.release()

        assertEquals(1, told)
        assertFalse(SharedStatusNotice.isHeldByRecording)
    }

    /** The stop path can run twice (a Stop, then onDestroy); only the first may re-post. */
    @Test
    fun releasing_when_nothing_is_held_tells_nobody() {
        var told = 0
        SharedStatusNotice.onReleased = { told++ }

        SharedStatusNotice.release()

        assertEquals(0, told)
    }

    @Test
    fun the_id_is_the_one_the_keep_alive_already_uses() {
        assertEquals(4720, SharedStatusNotice.ID)
        assertNull(SharedStatusNotice.onReleased)
    }

    /**
     * A leftover recording notification can sit on the shared id after the call (LAVA LXX508): the STOP's
     * own post, delivered by system_server after "Ready" was put back. The keep-alive checks what is
     * actually posted and puts "Ready" back when it is not its own.
     */
    @Test
    fun a_posted_notification_from_another_channel_is_stale_when_no_call_holds_the_id() {
        assertTrue(SharedStatusNotice.isStale(postedChannelId = "recording_channel_service", ownChannelId = "recorder_keepalive"))
    }

    @Test
    fun the_keep_alives_own_notification_is_not_stale() {
        assertFalse(SharedStatusNotice.isStale(postedChannelId = "recorder_keepalive", ownChannelId = "recorder_keepalive"))
    }

    @Test
    fun nothing_posted_is_not_stale() {
        assertFalse(SharedStatusNotice.isStale(postedChannelId = null, ownChannelId = "recorder_keepalive"))
    }

    /** During a recorded call the recording's notification is exactly what should be showing. */
    @Test
    fun the_recording_notification_is_not_stale_while_a_call_holds_the_id() {
        SharedStatusNotice.claim(notification("Recording call"))
        assertFalse(SharedStatusNotice.isStale(postedChannelId = "recording_channel_service", ownChannelId = "recorder_keepalive"))
    }

    /** An orphaned command ends its service without ever claiming; the keep-alive must still be told. */
    @Test
    fun a_refresh_request_tells_the_keep_alive_even_when_nothing_was_claimed() {
        var told = 0
        SharedStatusNotice.onReleased = { told++ }

        SharedStatusNotice.requestRefresh()

        assertEquals(1, told)
    }

    @Test
    fun a_refresh_request_during_a_recorded_call_tells_nobody() {
        var told = 0
        SharedStatusNotice.onReleased = { told++ }
        SharedStatusNotice.claim(notification("Recording call"))

        SharedStatusNotice.requestRefresh()

        assertEquals(0, told)
    }
}
