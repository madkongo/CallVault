package com.baba.callvault.ui.viewmodels

import com.baba.callvault.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.baba.callvault.services.recording.SharedStatusNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UI-1: while a call is recorded Home must not say "Ready to record". */
class HomeStatusRecordingTest {

    @Test
    fun `a live recording is the recording status`() =
        assertEquals(HomeViewModel.HomeStatus.RECORDING, HomeViewModel.statusForLive(SharedStatusNotice.Live.RECORDING))

    @Test
    fun `a paused recording is the paused status`() =
        assertEquals(HomeViewModel.HomeStatus.RECORDING_PAUSED, HomeViewModel.statusForLive(SharedStatusNotice.Live.PAUSED))

    @Test
    fun `no live recording leaves the normal checks to decide, so a down recorder still shows`() =
        assertNull(HomeViewModel.statusForLive(SharedStatusNotice.Live.NONE))

    @Test
    fun `the recording status reuses the notification wording`() {
        assertEquals(R.string.recording_notification_title, HomeViewModel.HomeStatus.RECORDING.titleResId)
        assertEquals(R.string.recording_notification_recording, HomeViewModel.HomeStatus.RECORDING.suggestionResId)
        assertEquals(R.string.recording_toast_paused, HomeViewModel.HomeStatus.RECORDING_PAUSED.titleResId)
        assertEquals(R.string.voip_recording_paused, HomeViewModel.HomeStatus.RECORDING_PAUSED.suggestionResId)
    }

    @Test
    fun `a recording starting or ending triggers a recompute, the current value does not`() = runBlocking {
        val live = MutableStateFlow(SharedStatusNotice.Live.NONE)
        var recomputes = 0
        val job = launch(Dispatchers.Unconfined) {
            ReadinessRecompute.triggers(flowOf(true), emptyFlow(), recordingLive = live).collect { recomputes++ }
        }
        assertEquals("the value Home just read is skipped", 0, recomputes)
        live.value = SharedStatusNotice.Live.RECORDING
        assertEquals(1, recomputes)
        live.value = SharedStatusNotice.Live.NONE
        assertEquals(2, recomputes)
        job.cancel()
    }
}
