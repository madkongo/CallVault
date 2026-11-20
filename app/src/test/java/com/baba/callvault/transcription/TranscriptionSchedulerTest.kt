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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.TranscriptionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scheduling, and — the part that is easy to get wrong — stopping.
 *
 * WorkManager has no "stop this execution but keep the schedule" call. Cancelling the periodic work
 * to stop a run in progress also deletes the nightly sweep, so a user who once tapped Cancel would
 * silently never get another automatic transcription.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    private fun sweepStates(): List<WorkInfo.State> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TranscriptionScheduler.WORK_NAME)
            .get()
            .map { it.state }

    @Test
    fun automatic_mode_schedules_the_nightly_sweep() {
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.AUTOMATIC)

        TranscriptionScheduler.apply(context)

        assertEquals(listOf(WorkInfo.State.ENQUEUED), sweepStates())
    }

    @Test
    fun manual_mode_leaves_no_sweep_scheduled() {
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.MANUAL)

        TranscriptionScheduler.apply(context)

        assertTrue(sweepStates().none { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun stopping_a_run_in_progress_keeps_the_nightly_sweep() {
        // The trap: cancelUniqueWork on periodic work removes the schedule along with the run, so
        // one tap on Cancel would quietly end automatic transcription for good.
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.AUTOMATIC)
        TranscriptionScheduler.apply(context)

        runBlocking { TranscriptionScheduler.stopNow(context) }

        assertEquals(listOf(WorkInfo.State.ENQUEUED), sweepStates())
    }

    @Test
    fun stopping_a_run_in_manual_mode_does_not_create_a_sweep() {
        // Re-applying the schedule must respect the mode: someone on Manual asked for no sweeps.
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.MANUAL)
        TranscriptionScheduler.runNow(context, "call.ogg")

        runBlocking { TranscriptionScheduler.stopNow(context) }

        assertTrue(sweepStates().none { it == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun stopping_cancels_the_user_requested_run() {
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.MANUAL)
        TranscriptionScheduler.runNow(context, "call.ogg")

        runBlocking { TranscriptionScheduler.stopNow(context) }

        val manual = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TranscriptionScheduler.MANUAL_WORK_NAME)
            .get()
        assertTrue(manual.all { it.state.isFinished })
    }

    // -- C127: a new hour moves the nightly run ------------------------------------------------

    private fun sweepInfo(): WorkInfo =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(TranscriptionScheduler.WORK_NAME).get().single()

    @Test
    fun changing_the_nightly_hour_moves_the_next_run() {
        val prefs = AppPreferences(context)
        prefs.setTranscriptionMode(TranscriptionMode.AUTOMATIC)
        prefs.setTranscriptionHour(2)
        prefs.setTranscriptionMinute(0)
        TranscriptionScheduler.apply(context)
        val before = sweepInfo().nextScheduleTimeMillis

        prefs.setTranscriptionHour(14)
        TranscriptionScheduler.apply(context)
        val after = sweepInfo().nextScheduleTimeMillis

        // 12 hours apart, give or take the minutes the test itself takes to run.
        val hours = Math.abs(after - before) / 3_600_000.0
        assertTrue("the next run did not move (moved $hours h)", hours in 11.5..12.5)
    }

    @Test
    fun applying_an_unchanged_schedule_leaves_the_queued_run_alone() {
        val prefs = AppPreferences(context)
        prefs.setTranscriptionMode(TranscriptionMode.AUTOMATIC)
        TranscriptionScheduler.apply(context)
        val first = sweepInfo().id

        TranscriptionScheduler.apply(context)

        assertEquals(first, sweepInfo().id)
    }

    @Test
    fun decide_keeps_an_unchanged_signature_and_replaces_a_changed_or_missing_one() {
        val keep = TranscriptionScheduler.decide("2:0/charging/NOT_REQUIRED", "2:0/charging/NOT_REQUIRED")
        assertEquals(androidx.work.ExistingPeriodicWorkPolicy.KEEP, keep)
        val changed = TranscriptionScheduler.decide("2:0/charging/NOT_REQUIRED", "3:0/charging/NOT_REQUIRED")
        assertEquals(androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, changed)
        val first = TranscriptionScheduler.decide(null, "2:0/charging/NOT_REQUIRED")
        assertEquals(androidx.work.ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, first)
    }

    @Test
    fun the_signature_covers_everything_that_changes_when_or_whether_the_sweep_runs() {
        val base = TranscriptionScheduler.signature(2, 0, requiresCharging = true, androidx.work.NetworkType.NOT_REQUIRED)
        assertTrue(base != TranscriptionScheduler.signature(2, 30, true, androidx.work.NetworkType.NOT_REQUIRED))
        assertTrue(base != TranscriptionScheduler.signature(2, 0, false, androidx.work.NetworkType.NOT_REQUIRED))
        assertTrue(base != TranscriptionScheduler.signature(2, 0, true, androidx.work.NetworkType.UNMETERED))
    }

    // -- C124: an after-call run is its own work, not a link in the tap chain ---------------------

    private fun manualInfos() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(TranscriptionScheduler.MANUAL_WORK_NAME).get()

    @Test
    fun an_after_call_run_waiting_for_a_charger_does_not_block_a_tapped_run() {
        val prefs = AppPreferences(context)
        prefs.setTranscriptionMode(TranscriptionMode.AFTER_EACH_CALL)
        prefs.setTranscriptionRequiresCharging(true)

        TranscriptionScheduler.transcribeAfterCallIfEnabled(context, "call1.ogg")
        TranscriptionScheduler.runNow(context, "tapped.ogg", userRequested = true)

        val tapped = manualInfos().single()
        // Not BLOCKED behind the charger-bound after-call run.
        assertEquals(WorkInfo.State.ENQUEUED, tapped.state)
        val afterCall = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TranscriptionScheduler.AFTER_CALL_WORK_PREFIX + "call1.ogg").get().single()
        assertTrue(afterCall.tags.contains(TranscriptionScheduler.TAG_RUN))
    }

    @Test
    fun the_same_call_queued_twice_is_one_after_call_run() {
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.AFTER_EACH_CALL)

        TranscriptionScheduler.transcribeAfterCallIfEnabled(context, "call1.ogg")
        TranscriptionScheduler.transcribeAfterCallIfEnabled(context, "call1.ogg")

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TranscriptionScheduler.AFTER_CALL_WORK_PREFIX + "call1.ogg").get()
        assertEquals(1, infos.size)
    }

    @Test
    fun stopping_also_cancels_a_queued_after_call_run() {
        AppPreferences(context).setTranscriptionMode(TranscriptionMode.AFTER_EACH_CALL)
        TranscriptionScheduler.transcribeAfterCallIfEnabled(context, "call1.ogg")

        runBlocking { TranscriptionScheduler.stopNow(context) }

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TranscriptionScheduler.AFTER_CALL_WORK_PREFIX + "call1.ogg").get()
        assertTrue(infos.all { it.state.isFinished })
    }
}
