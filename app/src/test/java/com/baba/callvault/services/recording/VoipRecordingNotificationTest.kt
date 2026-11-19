/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.LocaleManager
import android.app.NotificationManager
import android.os.LocaleList
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * BabApps item 123: an app call showed two notifications ("Recording a LINE call" with Pause/Mark/Stop, and the
 * keep-alive's "Recording VoIP call"), the first in English on a Chinese-language app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class VoipRecordingNotificationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = app

    private fun manager() = app.getSystemService(NotificationManager::class.java)

    @After
    fun reset() {
        SharedStatusNotice.resetForTest()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
    }

    private fun postCall(appLabel: String? = "LINE") {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        VoipRecordingNotification.show(context, appLabel)
    }

    @Test
    fun an_app_call_posts_one_notification_under_the_shared_id_and_claims_it() {
        postCall()

        assertEquals("exactly one CallVault notice during the call", 1, shadowOf(manager()).allNotifications.size)
        assertNotNull(shadowOf(manager()).getNotification(SharedStatusNotice.ID))
        assertNull("nothing under the old id", shadowOf(manager()).getNotification(5))
        assertTrue(SharedStatusNotice.isHeldByRecording)
    }

    @Test
    fun pause_mark_and_stop_are_still_on_it() {
        postCall()

        val n = shadowOf(manager()).getNotification(SharedStatusNotice.ID)
        assertEquals(
            listOf(
                context.getString(R.string.general_pause),
                context.getString(R.string.general_flag),
                context.getString(R.string.general_stop),
            ),
            n.actions.map { it.title.toString() },
        )
    }

    @Test
    fun the_keep_alive_is_given_the_call_notice_instead_of_its_own_while_the_call_lasts() {
        postCall()
        val own = NotificationCompat.Builder(context, "test").setContentTitle("Ready").build()

        assertFalse("Ready must not replace Pause/Mark/Stop", SharedStatusNotice.contentForKeepAlive(own) === own)
    }

    @Test
    fun the_end_of_the_call_hands_the_id_back_so_ready_returns() {
        var told = 0
        SharedStatusNotice.onReleased = { told++ }
        postCall()

        VoipRecordingNotification.dismiss(context)

        assertFalse(SharedStatusNotice.isHeldByRecording)
        assertEquals(1, told)
    }

    @Test
    fun without_the_notification_permission_nothing_is_claimed() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        VoipRecordingNotification.show(context, "LINE")

        assertFalse(SharedStatusNotice.isHeldByRecording)
    }

    @Test
    fun the_text_follows_the_app_language_not_the_phone_language() {
        // What Settings > Language ends up doing on Android 13+: the system's per-app locale. A service has no
        // AppCompat delegate, so this is the only place it can read it from.
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-CN")
        val chinese = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) },
        )
        val expectedTitle = chinese.getString(R.string.voip_recording_title_named, "LINE")
        val englishTitle = context.getString(R.string.voip_recording_title_named, "LINE")
        assertNotEquals("the test needs a language that differs from the phone's", englishTitle, expectedTitle)

        val n = VoipRecordingNotification.build(context, "LINE", marks = 0, paused = false, heldForCall = false)

        assertEquals(expectedTitle, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(chinese.getString(R.string.general_stop), n.actions.last().title.toString())
    }

    @Test
    fun theNoticeGoesThroughTheKeepAlivesForegroundPost_notNotify() {
        // Review C23 / Q49: on Android 17 Pixels notify() on the keep-alive's foreground id does not replace what is
        // shown, so Pause, Mark and Stop never appeared (#73). With the keep-alive running it posts the notice itself.
        val posted = mutableListOf<Notification>()
        SharedStatusNotice.foregroundPoster = { posted.add(it); true }

        postCall()

        assertEquals(1, posted.size)
        assertTrue(SharedStatusNotice.isHeldBy(SharedStatusNotice.Owner.VOIP))
        assertNull(manager().activeNotifications.firstOrNull { it.id == SharedStatusNotice.ID })
    }

    @Test
    fun withoutTheKeepAliveItFallsBackToNotify() {
        SharedStatusNotice.foregroundPoster = { false }
        postCall()
        assertNotNull(manager().activeNotifications.firstOrNull { it.id == SharedStatusNotice.ID })
    }
}
