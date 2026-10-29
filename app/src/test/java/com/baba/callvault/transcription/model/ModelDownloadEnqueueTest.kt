/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [ModelDownloadWorker.enqueue] actually queues for each answer to the mobile-data question.
 *
 * The test WorkManager never meets constraints on its own, so each request stays ENQUEUED and its
 * constraints can be read back; the worker never runs and nothing is fetched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelDownloadEnqueueTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = TranscriptionModel.DEFAULT

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build()
        )
    }

    private fun work(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ModelDownloadWorker.workNameFor(model))
            .get()

    private fun liveWork(): List<WorkInfo> = work().filter { !it.state.isFinished }

    @Test
    fun `the default waits for wifi`() {
        ModelDownloadWorker.enqueue(context, model)

        val queued = liveWork().single()
        assertEquals(NetworkType.UNMETERED, queued.constraints.requiredNetworkType)
        assertTrue(queued.constraints.requiresStorageNotLow())
    }

    @Test
    fun `wait for wifi needs an unmetered network`() {
        ModelDownloadWorker.enqueue(context, model, DownloadNetwork.WIFI_ONLY)

        assertEquals(NetworkType.UNMETERED, liveWork().single().constraints.requiredNetworkType)
    }

    @Test
    fun `download now on mobile data needs only a connection`() {
        ModelDownloadWorker.enqueue(context, model, DownloadNetwork.ANY)

        val queued = liveWork().single()
        assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)
        assertTrue(queued.constraints.requiresStorageNotLow())
    }

    @Test
    fun `a cancelled wifi wait is followed by a mobile data download`() {
        // The only way to a Download button from "Waiting for Wi-Fi" is its Cancel, which finishes
        // the request, so the next tap's choice must be the one that is queued.
        ModelDownloadWorker.enqueue(context, model, DownloadNetwork.WIFI_ONLY)
        ModelDownloadWorker.cancel(context, model)

        ModelDownloadWorker.enqueue(context, model, DownloadNetwork.ANY)

        assertEquals(NetworkType.CONNECTED, liveWork().single().constraints.requiredNetworkType)
    }

    @Test
    fun `a second tap while one is queued keeps the first request`() {
        // KEEP on purpose: replacing could cancel a running worker mid-write to the .part file.
        ModelDownloadWorker.enqueue(context, model, DownloadNetwork.WIFI_ONLY)
        val first = liveWork().single().id

        ModelDownloadWorker.enqueue(context, model, DownloadNetwork.ANY)

        assertEquals(first, liveWork().single().id)
    }
}
