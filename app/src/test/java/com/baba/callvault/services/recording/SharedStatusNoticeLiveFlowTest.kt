package com.baba.callvault.services.recording

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI-1: Home follows "a call is being recorded" live, through [SharedStatusNotice.heldByRecordingFlow]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedStatusNoticeLiveFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun notification(): Notification = NotificationCompat.Builder(context, "test").setContentTitle("x").build()

    @After
    fun reset() = SharedStatusNotice.resetForTest()

    @Test
    fun `the flow follows claim and release`() {
        assertFalse(SharedStatusNotice.heldByRecordingFlow.value)
        SharedStatusNotice.claim(SharedStatusNotice.Owner.CARRIER, notification())
        assertTrue(SharedStatusNotice.heldByRecordingFlow.value)
        SharedStatusNotice.claim(SharedStatusNotice.Owner.VOIP, notification())
        SharedStatusNotice.release(SharedStatusNotice.Owner.CARRIER)
        assertTrue("an app call still holds it", SharedStatusNotice.heldByRecordingFlow.value)
        SharedStatusNotice.release(SharedStatusNotice.Owner.VOIP)
        assertFalse(SharedStatusNotice.heldByRecordingFlow.value)
    }

    @Test
    fun `reset clears the flow`() {
        SharedStatusNotice.claim(SharedStatusNotice.Owner.CARRIER, notification())
        SharedStatusNotice.resetForTest()
        assertFalse(SharedStatusNotice.heldByRecordingFlow.value)
    }
}
