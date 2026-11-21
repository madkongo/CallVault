package com.baba.callvault.services.recording

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.R
import org.junit.After
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

/**
 * R29: error notices must not overwrite each other, must open the app, and a repeat may be quiet only for the
 * notices that are not "this call was NOT recorded".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ErrorNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val shown get() = shadowOf(manager).allNotifications
    private var now = 1_000_000L

    private val critical get() = app.getString(R.string.recording_error_daemon_died)
    private val minor get() = app.getString(R.string.recording_error_drive_copy_failed)

    @Before
    fun fresh() {
        manager.cancelAll()
        now = 1_000_000L
        RecordingNotificationHelper.clockForTest = { now }
        RecordingNotificationHelper.resetErrorHistoryForTest()
        RecordingNotificationHelper.resetEnsuredForTest()
        RecordingNotificationHelper.ensureChannels(app)
    }

    @After
    fun restoreClock() {
        RecordingNotificationHelper.clockForTest = null
    }

    private fun isQuiet(n: Notification) = n.groupAlertBehavior != Notification.GROUP_ALERT_ALL

    @Test
    fun `a minor error does not replace this call was not recorded`() {
        val helper = RecordingNotificationHelper(app)
        helper.showErrorNotification(critical)
        helper.showErrorNotification(minor)
        assertEquals(2, shown.size)
    }

    @Test
    fun `the same error repeated updates one notice instead of stacking`() {
        val helper = RecordingNotificationHelper(app)
        helper.showErrorNotification(critical)
        helper.showErrorNotification(critical)
        assertEquals(1, shown.size)
    }

    @Test
    fun `a repeat of a not-recorded notice after a recovery still alerts`() {
        val helper = RecordingNotificationHelper(app)
        helper.showErrorNotification(critical)
        now += 30_000 // a good call in between does not clear it; the cause came back inside the quiet window
        helper.showErrorNotification(critical)

        val n = shown.single()
        assertFalse("must alert", isQuiet(n))
        assertEquals(0, n.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun `tap does not clear a not-recorded notice and it does not expire`() {
        RecordingNotificationHelper(app).showErrorNotification(critical)

        val n = shown.single()
        assertNotNull("tap opens Home", n.contentIntent)
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(0L, n.timeoutAfter)
    }

    @Test
    fun `an unknown message is treated as not recorded`() {
        RecordingNotificationHelper(app).showErrorNotification("something new")
        val n = shown.single()
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
        assertFalse(isQuiet(n))
    }

    @Test
    fun `a non-critical repeat within two minutes is quiet`() {
        val helper = RecordingNotificationHelper(app)
        helper.showErrorNotification(minor)
        now += 60_000
        helper.showErrorNotification(minor)

        assertTrue(isQuiet(shown.single()))
    }

    @Test
    fun `a non-critical repeat after two minutes alerts again`() {
        val helper = RecordingNotificationHelper(app)
        helper.showErrorNotification(minor)
        now += RecordingNotificationHelper.QUIET_REPEAT_MS + 1
        helper.showErrorNotification(minor)

        val n = shown.single()
        assertFalse(isQuiet(n))
        assertNotNull(n.contentIntent)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(RecordingNotificationHelper.ERROR_TIMEOUT_MS, n.timeoutAfter)
    }
}
