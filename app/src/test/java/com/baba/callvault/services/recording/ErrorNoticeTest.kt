package com.baba.callvault.services.recording

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** R29: error notices must not overwrite each other, must open the app, and must expire. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ErrorNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val shown get() = shadowOf(manager).allNotifications

    @Before
    fun fresh() {
        manager.cancelAll()
        RecordingNotificationHelper.resetEnsuredForTest()
        RecordingNotificationHelper.ensureChannels(app)
    }

    @Test
    fun `a minor error does not replace this call was not recorded`() {
        val helper = RecordingNotificationHelper(app)

        helper.showErrorNotification("This call is NOT being recorded")
        helper.showErrorNotification("Could not copy to Drive")

        assertEquals(2, shown.size)
    }

    @Test
    fun `the same error repeated updates one notice instead of stacking`() {
        val helper = RecordingNotificationHelper(app)

        helper.showErrorNotification("This call is NOT being recorded")
        helper.showErrorNotification("This call is NOT being recorded")

        assertEquals(1, shown.size)
    }

    @Test
    fun `an error already on screen is known so repeats stay silent`() {
        val helper = RecordingNotificationHelper(app)
        assertFalse(helper.isErrorShown("x"))

        helper.showErrorNotification("x")

        assertTrue(helper.isErrorShown("x"))
        assertFalse(helper.isErrorShown("y"))
    }

    @Test
    fun `the notice opens the app, goes away on tap and expires`() {
        RecordingNotificationHelper(app).showErrorNotification("boom")

        val n = shown.single()
        assertNotNull("tap target", n.contentIntent)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(RecordingNotificationHelper.ERROR_TIMEOUT_MS, n.timeoutAfter)
    }
}
