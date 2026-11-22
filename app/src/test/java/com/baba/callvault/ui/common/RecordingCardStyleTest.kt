package com.baba.callvault.ui.common

import com.baba.callvault.services.recording.SharedStatusNotice
import com.baba.callvault.ui.viewmodels.HomeViewModel.HomeStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCardStyleTest {

    @After
    fun reset() = SharedStatusNotice.resetForTest()

    @Test
    fun `recording is red and animated`() =
        assertEquals(RecordingCardStyle(blinkDot = true, animateBars = true), recordingCardStyle(HomeStatus.RECORDING, true))

    @Test
    fun `paused is red and still`() =
        assertEquals(RecordingCardStyle(blinkDot = false, animateBars = false), recordingCardStyle(HomeStatus.RECORDING_PAUSED, true))

    @Test
    fun `reduced motion stills a live recording`() =
        assertEquals(RecordingCardStyle(blinkDot = false, animateBars = false), recordingCardStyle(HomeStatus.RECORDING, false))

    @Test
    fun `other statuses keep the normal card`() {
        assertNull(recordingCardStyle(HomeStatus.READY, true))
        assertNull(recordingCardStyle(HomeStatus.STARTING, true))
        assertNull(recordingCardStyle(HomeStatus.NO_FOLDER, true))
    }

    @Test
    fun `animator scale zero turns animations off`() {
        assertFalse(animationsEnabled(0f))
        assertTrue(animationsEnabled(1f))
        assertTrue(animationsEnabled(0.5f))
    }

    @Test
    fun `timer formats minutes and seconds`() {
        assertEquals("00:00", formatRecordingElapsed(0))
        assertEquals("00:59", formatRecordingElapsed(59_999))
        assertEquals("02:41", formatRecordingElapsed(161_000))
        assertEquals("59:59", formatRecordingElapsed(3_599_000))
    }

    @Test
    fun `timer shows hours over an hour`() {
        assertEquals("1:00:00", formatRecordingElapsed(3_600_000))
        assertEquals("12:34:56", formatRecordingElapsed(45_296_000))
    }

    @Test
    fun `a negative elapsed is shown as zero`() = assertEquals("00:00", formatRecordingElapsed(-5_000))

    @Test
    fun `elapsed counts from the start and freezes while paused`() {
        val o = SharedStatusNotice.Owner.CARRIER
        assertEquals(0L, SharedStatusNotice.liveElapsedMs(1_000))
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, nowMs = 1_000)
        assertEquals(5_000L, SharedStatusNotice.liveElapsedMs(6_000))
        // repeated RECORDING (e.g. a mark updating the notification) does not restart it
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, nowMs = 7_000)
        assertEquals(10_000L, SharedStatusNotice.liveElapsedMs(11_000))
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.PAUSED, nowMs = 11_000)
        assertEquals(10_000L, SharedStatusNotice.liveElapsedMs(60_000))
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, nowMs = 60_000)
        assertEquals(12_000L, SharedStatusNotice.liveElapsedMs(62_000))
    }

    @Test
    fun `elapsed resets when the recording ends`() {
        val o = SharedStatusNotice.Owner.VOIP
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, nowMs = 0)
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.NONE, nowMs = 9_000)
        assertEquals(0L, SharedStatusNotice.liveElapsedMs(20_000))
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, nowMs = 30_000)
        assertEquals(1_000L, SharedStatusNotice.liveElapsedMs(31_000))
    }
}
