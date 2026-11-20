/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.services.recording.RecordingForegroundService
import com.baba.callvault.services.recording.RecordingNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** N26: what happens when Android refuses the start of the recording service, with the call path as the caller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingServiceCommanderTest {

    private class RefusingContext(base: Context, private val refuseStart: Boolean, private val refuseStop: Boolean = false) :
        ContextWrapper(base) {
        var startForegroundCalls = 0
        var stopServiceCalls = 0
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent?): android.content.ComponentName? {
            startForegroundCalls++
            if (refuseStart) throw IllegalStateException("startForegroundService() not allowed")
            return null
        }
        override fun startService(service: Intent?): android.content.ComponentName? {
            if (refuseStart) throw IllegalStateException("startService not allowed")
            return null
        }
        override fun stopService(name: Intent?): Boolean {
            stopServiceCalls++
            if (refuseStop) throw IllegalStateException("stopService not allowed")
            return true
        }
    }

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun noticePosted(): Boolean {
        val nm = app.getSystemService(NotificationManager::class.java)
        return shadowOf(nm).getNotification(RecordingNotificationHelper.BATTERY_EXEMPTION_NOTIFICATION_ID) != null
    }

    @Test
    fun `a refused start does not throw, posts the notice and marks the refusal`() {
        val ctx = RefusingContext(app, refuseStart = true)
        RecordingServiceCommander(ctx).send(RecordingForegroundService.ACTION_START_RECORDING)
        assertEquals(1, ctx.startForegroundCalls)
        assertTrue(noticePosted())
        assertTrue(ForegroundStartGuard.refusedAt(app) > 0L)
    }

    @Test
    fun `a refused stop does not throw, tries stopService and sets no refusal`() {
        val ctx = RefusingContext(app, refuseStart = true)
        RecordingServiceCommander(ctx).send(RecordingForegroundService.ACTION_STOP_RECORDING)
        assertEquals(1, ctx.stopServiceCalls)
        assertEquals(0L, ForegroundStartGuard.refusedAt(app))
        assertFalse(noticePosted())
    }

    @Test
    fun `a stop whose fallback is also refused still does not throw`() {
        val ctx = RefusingContext(app, refuseStart = true, refuseStop = true)
        RecordingServiceCommander(ctx).send(RecordingForegroundService.ACTION_STOP_RECORDING)
        assertEquals(1, ctx.stopServiceCalls)
    }

    @Test
    fun `a later successful start clears the refusal`() {
        ForegroundStartGuard.markRefused(app, 5L)
        RecordingServiceCommander(RefusingContext(app, refuseStart = false))
            .send(RecordingForegroundService.ACTION_START_RECORDING)
        assertEquals(0L, ForegroundStartGuard.refusedAt(app))
    }

    @Test
    fun `the battery intent falls back to app settings when nothing handles the prompt`() {
        val intent = ForegroundStartGuard.bestBatteryIntent(app)
        assertNotNull(intent.action)
        assertNull(intent.component)
    }
}
