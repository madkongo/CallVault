package com.baba.callvault.system.health

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** R5: the one combined notice, posted, updated in place and cancelled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingRiskNoticeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val day = RecordingRiskNoticePolicy.DAY_MS

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("cv_risk_notice", 0).edit().clear().apply()
    }

    private fun shown() = shadowOf(manager).allNotifications

    @Test
    fun `a reason posts one notice with the fix action`() {
        val outcome = RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, now = 10 * day, reasons = listOf(RiskReason.BATTERY_LIMITS))

        val n = shown().single()
        assertTrue(outcome.startsWith("posted"))
        assertEquals("Calls may not be recorded", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Battery limits are on", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Fix", n.actions.single().title.toString())
        assertNotNull(n.contentIntent)
    }

    @Test
    fun `a changed set a day later replaces the same notice with a list`() {
        RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 10 * day, listOf(RiskReason.BATTERY_LIMITS))
        val outcome = RecordingRiskNotice.evaluate(
            app, RecordingRiskNotice.Trigger.TICK, 11 * day, listOf(RiskReason.BATTERY_LIMITS, RiskReason.BACKGROUND_RESTRICTED),
        )

        val n = shown().single()
        assertTrue(outcome.startsWith("updated"))
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertEquals("• Battery limits are on\n• Android set CallVault to \"Restricted\"", text)
    }

    @Test
    fun `the same reasons again are left alone`() {
        RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 10 * day, listOf(RiskReason.BATTERY_LIMITS))
        val outcome = RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 20 * day, listOf(RiskReason.BATTERY_LIMITS))

        assertTrue(outcome.startsWith("skipped"))
        assertEquals(1, shown().size)
    }

    @Test
    fun `no reason cancels it, and a return posts again`() {
        RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 10 * day, listOf(RiskReason.BATTERY_LIMITS))
        val outcome = RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 20 * day, emptyList())
        assertTrue(outcome.startsWith("cancelled"))
        assertTrue(shown().isEmpty())

        RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 40 * day, listOf(RiskReason.BATTERY_LIMITS))
        assertEquals(1, shown().size)
    }

    @Test
    fun `notifications being off alone posts nothing`() {
        val outcome = RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 10 * day, listOf(RiskReason.NOTIFICATIONS_OFF))
        assertTrue(outcome.startsWith("cancelled"))
        assertTrue(shown().isEmpty())
    }

    @Test
    fun `the tick does nothing between daily checks`() {
        RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 10 * day, emptyList())
        val outcome = RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.TICK, 10 * day + 60_000, listOf(RiskReason.BATTERY_LIMITS))
        assertTrue(outcome.startsWith("not due"))
        assertTrue(shown().isEmpty())
    }

    @Test
    fun `without the notification permission nothing is shown and it is tried again later`() {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val outcome = RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 10 * day, listOf(RiskReason.BATTERY_LIMITS))
        assertTrue(outcome, outcome.startsWith("skipped"))

        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        RecordingRiskNotice.evaluate(app, RecordingRiskNotice.Trigger.BOOT, 10 * day + 1000, listOf(RiskReason.BATTERY_LIMITS))
        assertEquals(1, shown().size)
    }

    @Test
    fun `its id is not the keep-alive's and not another notice's`() {
        assertTrue(SilentFailureNotifier.ALL_IDS.contains(4734))
        assertTrue(!SilentFailureNotifier.ALL_IDS.contains(4720))
    }
}
