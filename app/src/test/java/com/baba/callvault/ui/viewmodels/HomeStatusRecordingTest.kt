package com.baba.callvault.ui.viewmodels

import com.baba.callvault.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** UI-1: while a call is recorded Home must not say "Ready to record". */
class HomeStatusRecordingTest {

    @Test
    fun `a live recording replaces a ready status`() =
        assertEquals(HomeViewModel.HomeStatus.RECORDING, HomeViewModel.statusWhileRecording(HomeViewModel.HomeStatus.READY, true))

    @Test
    fun `a live recording also replaces a setup warning`() =
        assertEquals(HomeViewModel.HomeStatus.RECORDING, HomeViewModel.statusWhileRecording(HomeViewModel.HomeStatus.NO_FOLDER, true))

    @Test
    fun `no live recording leaves the status alone`() =
        assertEquals(HomeViewModel.HomeStatus.READY, HomeViewModel.statusWhileRecording(HomeViewModel.HomeStatus.READY, false))

    @Test
    fun `the recording status reuses the notification wording`() {
        assertEquals(R.string.recording_notification_title, HomeViewModel.HomeStatus.RECORDING.titleResId)
        assertEquals(R.string.recording_notification_recording, HomeViewModel.HomeStatus.RECORDING.suggestionResId)
    }

    @Test
    fun `a recording starting or ending triggers a recompute, the current value does not`() = runBlocking {
        val live = MutableStateFlow(false)
        var recomputes = 0
        val job = launch(Dispatchers.Unconfined) {
            ReadinessRecompute.triggers(flowOf(true), emptyFlow(), recordingLive = live).collect { recomputes++ }
        }
        assertEquals("the value Home just read is skipped", 0, recomputes)
        live.value = true
        assertEquals(1, recomputes)
        live.value = false
        assertEquals(2, recomputes)
        job.cancel()
    }
}
