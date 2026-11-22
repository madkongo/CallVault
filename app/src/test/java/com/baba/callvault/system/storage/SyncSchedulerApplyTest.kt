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
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Review C25: [SyncScheduler.apply] runs on the main thread (app start, Settings, end of a call), so it must
 * never wait on WorkManager's database. And without that read, an unchanged schedule whose request is gone
 * must still get a live request again (WorkManager's KEEP enqueues when nothing live holds the name).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSchedulerApplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppPreferences(context).apply {
            setStorageTarget(StorageTarget.BOTH)
            setSyncScheduleMode(SyncScheduleMode.DAILY)
        }
    }

    @After
    fun tearDown() = unmockkObject(WorkManager.Companion)

    @Test
    fun `apply returns at once even when WorkManager's database does not answer`() {
        val never = SettableFuture.create<List<WorkInfo>>()
        val pendingResult = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation> { every { result } returns pendingResult }
        val wm = mockk<WorkManager>(relaxed = true) {
            every { getWorkInfosForUniqueWork(any()) } returns never
            every { enqueueUniquePeriodicWork(any(), any(), any()) } returns operation
        }
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns wm

        val wallStart = System.nanoTime()
        SyncScheduler.apply(context)
        val tookMs = (System.nanoTime() - wallStart) / 1_000_000

        assertTrue("apply blocked the caller for ${tookMs}ms", tookMs < 1_000)
    }

    @Test
    fun `an unchanged schedule whose request was cancelled gets a live request again`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        val wm = WorkManager.getInstance(context)
        SyncScheduler.apply(context)
        wm.cancelUniqueWork(SyncScheduler.WORK_NAME).result.get()
        assertTrue(wm.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get().none { it.state == WorkInfo.State.ENQUEUED })

        SyncScheduler.apply(context) // same signature as stored

        val live = wm.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, live.size)
    }

    @Test
    fun `an unchanged schedule with a live request keeps that same request`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        val wm = WorkManager.getInstance(context)
        SyncScheduler.apply(context)
        val first = wm.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get().single().id

        SyncScheduler.apply(context)

        assertEquals(first, wm.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get().single { it.state == WorkInfo.State.ENQUEUED }.id)
    }

    @Test
    fun `switching to device-only clears a posted Drive backup notice`() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "test")
        assertEquals(1, shadowOf(nm).allNotifications.size)
        AppPreferences(context).setStorageTarget(StorageTarget.LOCAL)

        SyncScheduler.apply(context)

        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
