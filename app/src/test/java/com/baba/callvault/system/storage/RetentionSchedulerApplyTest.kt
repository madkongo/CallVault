/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C126: every process start re-ran [RetentionScheduler.apply] with CANCEL_AND_REENQUEUE, so the job that had
 * just started the process was deleted and pushed a day out before it could run. An unchanged schedule is now left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetentionSchedulerApplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sig = RetentionScheduler.signature(3, 30, 30, 0, 0L, "Asia/Jerusalem")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        context.getSharedPreferences("cv_retention_scheduler", Context.MODE_PRIVATE).edit().clear().commit()
        AppPreferences(context).apply {
            setRetentionLocalDays(30)
            setRetentionDriveDays(0)
            setStorageCapBytes(0L)
            setRetentionTimeHour(3)
            setRetentionTimeMinute(30)
        }
    }

    private fun live() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RetentionScheduler.WORK_NAME).get()
        .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

    @Test
    fun `unchanged signature is kept`() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, RetentionScheduler.decide(sig, sig, force = false).policy)
    }

    @Test
    fun `no stored signature, a changed one, or a forced re-anchor re-enqueues`() {
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, RetentionScheduler.decide(null, sig, false).policy)
        for (changed in listOf(
            RetentionScheduler.signature(4, 30, 30, 0, 0L, "Asia/Jerusalem"),
            RetentionScheduler.signature(3, 31, 30, 0, 0L, "Asia/Jerusalem"),
            RetentionScheduler.signature(3, 30, 31, 0, 0L, "Asia/Jerusalem"),
            RetentionScheduler.signature(3, 30, 30, 5, 0L, "Asia/Jerusalem"),
            RetentionScheduler.signature(3, 30, 30, 0, 1L, "Asia/Jerusalem"),
            RetentionScheduler.signature(3, 30, 30, 0, 0L, "Europe/London"),
        )) {
            assertNotEquals(sig, changed)
            assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, RetentionScheduler.decide(sig, changed, false).policy)
        }
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, RetentionScheduler.decide(sig, sig, force = true).policy)
        assertNotEquals(ExistingPeriodicWorkPolicy.UPDATE, RetentionScheduler.decide(sig, sig, true).policy)
    }

    @Test
    fun `a second start with unchanged settings keeps the same request`() {
        RetentionScheduler.apply(context)
        val first = live().single().id

        RetentionScheduler.apply(context)

        assertEquals(first, live().single().id)
    }

    @Test
    fun `a changed run-at time replaces the request at once`() {
        RetentionScheduler.apply(context)
        val first = live().single().id

        AppPreferences(context).setRetentionTimeHour(14)
        RetentionScheduler.apply(context)

        assertNotEquals(first, live().single().id)
    }

    @Test
    fun `a time or zone change forces a new request even when the settings did not change`() {
        RetentionScheduler.apply(context)
        val first = live().single().id

        RetentionScheduler.apply(context, forceReanchor = true)

        assertNotEquals(first, live().single().id)
    }

    @Test
    fun `an unchanged schedule whose request was cancelled gets a live request again`() {
        RetentionScheduler.apply(context)
        WorkManager.getInstance(context).cancelUniqueWork(RetentionScheduler.WORK_NAME).result.get()

        RetentionScheduler.apply(context)

        assertEquals(1, live().size)
    }

    @Test
    fun `turning retention off cancels the job and forgets the signature`() {
        RetentionScheduler.apply(context)
        AppPreferences(context).apply { setRetentionLocalDays(0); setRetentionDriveDays(0) }

        RetentionScheduler.apply(context)

        assertEquals(0, live().size)
        // turning it back on is a fresh enqueue, not a KEEP against a signature of the old job
        AppPreferences(context).setRetentionLocalDays(30)
        RetentionScheduler.apply(context)
        assertEquals(1, live().size)
    }
}
