/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review N36: a periodic worker that returns retry runs on WorkManager's backoff (up to 5 h), not on its
 * schedule, so the nightly sweep must succeed and queue a separate one-time retry instead. One-time runs
 * keep returning retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionWorkerPeriodicTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build()
        )
    }

    private fun run(vararg tags: String): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<TranscriptionWorker>(context).setTags(tags.toList()).build().doWork()
    }

    private fun retryInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TranscriptionScheduler.SWEEP_RETRY_WORK_NAME).get()

    @Test
    fun a_periodic_run_that_must_wait_for_the_model_succeeds_and_queues_a_one_time_retry() {
        // No model is installed in the test, which is the retry path.
        val result = run(TranscriptionScheduler.TAG_PERIODIC)

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), retryInfos().map { it.state })
        assertFalse(retryInfos().single().tags.contains(TranscriptionScheduler.TAG_PERIODIC))
    }

    @Test
    fun a_one_time_run_that_must_wait_for_the_model_still_returns_retry() {
        val result = run()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(retryInfos().isEmpty())
    }

    @Test
    fun two_bad_nights_in_a_row_leave_one_queued_retry() {
        run(TranscriptionScheduler.TAG_PERIODIC)
        run(TranscriptionScheduler.TAG_PERIODIC)

        assertEquals(1, retryInfos().size)
    }

    @Test
    fun the_sweep_is_tagged_periodic_and_the_retry_is_not() {
        com.baba.callvault.data.AppPreferences(context)
            .setTranscriptionMode(com.baba.callvault.data.TranscriptionMode.AUTOMATIC)
        TranscriptionScheduler.apply(context)
        val sweep = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TranscriptionScheduler.WORK_NAME).get().single()
        assertTrue(sweep.tags.contains(TranscriptionScheduler.TAG_PERIODIC))
        assertTrue(sweep.tags.contains(TranscriptionScheduler.TAG_RUN))
    }

    @Test
    fun the_worker_source_returns_retry_only_where_it_has_checked_for_periodic_work() {
        val source = File("src/main/java/com/baba/callvault/transcription/TranscriptionWorker.kt").readLines()
        val offenders = source.withIndex().filter { (i, line) ->
            val code = line.trim()
            code.contains("Result.retry()") && !code.startsWith("*") && !code.startsWith("//") &&
                !(code.contains("isPeriodic") || source[i - 1].contains("!isPeriodic"))
        }
        assertTrue("unguarded Result.retry() at lines ${offenders.map { it.index + 1 }}", offenders.isEmpty())
    }
}
