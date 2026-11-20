/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review R3: a queued start retry exists after a refusal and is gone once the keep-alive is stopped on purpose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepAliveRetryWorkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun states(): List<WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("keep-alive-retry").get().map { it.state }

    @Test
    fun `a refusal queues one retry`() {
        KeepAliveRetry.schedule(context, 1, "test")
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states())
    }

    @Test
    fun `a later retry replaces the queued one`() {
        KeepAliveRetry.schedule(context, 1, "test")
        KeepAliveRetry.schedule(context, 2, "test")
        assertEquals(1, states().count { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `stopping the keep-alive on purpose cancels a queued retry`() {
        KeepAliveRetry.schedule(context, 1, "test")
        KeepAliveRetry.cancel(context)
        assertEquals(listOf(WorkInfo.State.CANCELLED), states())
    }

    @Test
    fun `no retry is queued past the limit`() {
        KeepAliveRetry.schedule(context, KeepAliveRetry.MAX_ATTEMPTS + 1, "test")
        assertEquals(emptyList<WorkInfo.State>(), states())
    }
}
