package com.baba.callvault.system.health

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.services.recording.RecordingForegroundService
import com.baba.callvault.services.recording.RecordingNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** R32: nothing private on the lock screen, and no stopping a recording from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockScreenPrivacyTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the cut-off notice shows no file name on the lock screen`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        SilentFailureNotifier.noteCutOffRecordingSaved(app, "Alice Cohen 052.m4a")

        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val public = n.publicVersion
        assertNotNull("a redacted public version", public)
        val shownLocked = public.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString() +
            public.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
        assertFalse(shownLocked, shownLocked.contains("Alice"))
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
    }

    @Test
    fun `stop pause and resume need the phone unlocked, record does not`() {
        assertTrue(RecordingNotificationHelper.requiresUnlock(RecordingForegroundService.ACTION_STOP_RECORDING))
        assertTrue(RecordingNotificationHelper.requiresUnlock(RecordingForegroundService.ACTION_PAUSE_RECORDING))
        assertTrue(RecordingNotificationHelper.requiresUnlock(RecordingForegroundService.ACTION_RESUME_RECORDING))
        assertFalse(RecordingNotificationHelper.requiresUnlock(RecordingForegroundService.ACTION_MANUAL_START))
        assertFalse(RecordingNotificationHelper.requiresUnlock(RecordingForegroundService.ACTION_FLAG_MOMENT))
    }
}
