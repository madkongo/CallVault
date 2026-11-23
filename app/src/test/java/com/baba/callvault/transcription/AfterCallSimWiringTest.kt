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
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.TranscriptionMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the one-SIM rule (issue #81) runs: only for after-call runs, and before the run gate, so its call-log
 * wait never holds the gate, blocks a tap, or shows the transcribing pill for a call it skips.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallSimWiringTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261112_143520_in_0541234567.ogg"
    private var asked = 0

    @Before
    fun setUp() {
        TranscriptionRunGate.reset()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build()
        )
        TranscriptionWorker.afterCallSimSkip = { _, _ -> asked++; true }
    }

    @After
    fun tearDown() {
        TranscriptionWorker.afterCallSimSkip = AfterCallSimGate::skipTranscription
        TranscriptionRunGate.reset()
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.MANUAL)
    }

    private fun runWorker(afterCall: Boolean, userRequested: Boolean = false): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<TranscriptionWorker>(context)
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_DISPLAY_NAME to name,
                    TranscriptionWorker.KEY_USER_REQUESTED to userRequested,
                    TranscriptionWorker.KEY_AFTER_CALL to afterCall,
                )
            )
            .build().doWork()
    }

    @Test
    fun a_skipped_after_call_run_finishes_even_while_another_run_holds_the_gate() {
        assertTrue(TranscriptionRunGate.tryEnter(TranscriptionRunGate.Kind.TAP))
        assertEquals(ListenableWorker.Result.success(), runWorker(afterCall = true))
        assertEquals(1, asked)
    }

    @Test
    fun a_skipped_after_call_run_never_enters_the_gate() {
        assertEquals(ListenableWorker.Result.success(), runWorker(afterCall = true))
        // The gate is free: an after-call run (which needs nothing else running) can enter at once.
        assertTrue(TranscriptionRunGate.tryEnter(TranscriptionRunGate.Kind.AFTER_CALL))
    }

    @Test
    fun a_tapped_run_never_asks_the_sim_rule() {
        runWorker(afterCall = false, userRequested = true)
        assertEquals(0, asked)
    }

    private fun afterCallFlags(): List<Boolean> {
        val dao = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
        return dao.getAllWorkSpecIds().mapNotNull { dao.getWorkSpec(it) }
            .map { it.input.getBoolean(TranscriptionWorker.KEY_AFTER_CALL, false) }
    }

    @Test
    fun only_the_after_call_request_carries_the_flag() {
        TranscriptionScheduler.runNow(context, displayName = name, userRequested = true)
        TranscriptionScheduler.runNow(context, displayName = name, userRequested = false)
        TranscriptionScheduler.runNow(context)
        TranscriptionScheduler.enqueueSweepRetry(context)
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.AUTOMATIC)
        TranscriptionScheduler.apply(context)
        assertTrue(afterCallFlags().isNotEmpty())
        assertFalse(afterCallFlags().any { it })

        AppPreferences(context).setTranscriptionMode(TranscriptionMode.AFTER_EACH_CALL)
        TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name)
        assertEquals(1, afterCallFlags().count { it })
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(TranscriptionScheduler.AFTER_CALL_WORK_PREFIX + name).get().isNotEmpty())
    }
}
