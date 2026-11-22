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

class LiveClockRestoreTest {
    private class FakeStore : SharedStatusNotice.ClockStore {
        var saved: SharedStatusNotice.SavedClock? = null
        var noCallAt = 0L
        var callBeganAt = 0L
        override fun liveCallBeganAt() = callBeganAt
        override fun lastNoCallAt() = noCallAt
        override fun load() = saved
        override fun save(clock: SharedStatusNotice.SavedClock?) { saved = clock }
    }

    private val store = FakeStore()
    private val o = SharedStatusNotice.Owner.CARRIER
    private val boot = 1_000_000_000L // wall clock at boot; wall = boot + elapsed

    @org.junit.Before
    fun attach() { SharedStatusNotice.resetForTest(); SharedStatusNotice.clockStore = store }

    @After
    fun reset() = SharedStatusNotice.resetForTest()

    private fun at(elapsed: Long) = elapsed to (boot + elapsed)

    /** A process kill: memory is lost, the stored clock stays. */
    private fun killProcess() { SharedStatusNotice.resetForTest(); SharedStatusNotice.clockStore = store }

    @Test
    fun `a fresh process joining the same ongoing call keeps its time`() {
        val (e0, w0) = at(10_000)
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, e0, w0)
        killProcess()
        val (e1, w1) = at(70_000)
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, e1, w1)
        assertEquals(60_000L, SharedStatusNotice.liveElapsedMs(e1))
        assertEquals(65_000L, SharedStatusNotice.liveElapsedMs(e1 + 5_000))
    }

    @Test
    fun `a paused clock restores frozen and resumes from its banked time`() {
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 0, boot)
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.PAUSED, 20_000, boot + 20_000)
        killProcess()
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.PAUSED, 50_000, boot + 50_000)
        assertEquals(20_000L, SharedStatusNotice.liveElapsedMs(90_000))
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 90_000, boot + 90_000)
        assertEquals(25_000L, SharedStatusNotice.liveElapsedMs(95_000))
    }

    @Test
    fun `a start saved before a reboot is ignored`() {
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 500_000, boot + 500_000)
        killProcess()
        // new boot: elapsedRealtime restarted, wall clock kept going
        val newBoot = boot + 3_600_000L
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 30_000, newBoot + 30_000)
        assertEquals(0L, SharedStatusNotice.liveElapsedMs(30_000))
    }

    @Test
    fun `a start older than the cap is ignored even in the same boot`() {
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 0, boot)
        killProcess()
        val late = 3 * 3_600_000L
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, late, boot + late)
        assertEquals(0L, SharedStatusNotice.liveElapsedMs(late))
    }

    @Test
    fun `an ended call leaves nothing to restore`() {
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 0, boot)
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.NONE, 30_000, boot + 30_000)
        assertNull(store.saved)
        killProcess()
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 60_000, boot + 60_000)
        assertEquals(0L, SharedStatusNotice.liveElapsedMs(60_000))
    }

    @Test
    fun `a call-free moment after the stored start means the old call ended`() {
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 0, boot)
        store.noCallAt = boot + 30_000 // phone idle, no call, after the start
        killProcess()
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 60_000, boot + 60_000)
        assertEquals(0L, SharedStatusNotice.liveElapsedMs(60_000))
    }

    @Test
    fun `a call-free moment before the stored start does not stop the restore`() {
        store.noCallAt = boot - 5_000
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 10_000, boot + 10_000)
        killProcess()
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 40_000, boot + 40_000)
        assertEquals(30_000L, SharedStatusNotice.liveElapsedMs(40_000))
    }

    @Test
    fun `a call that began after the stored clock started never inherits its time`() {
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 0, boot)
        store.callBeganAt = boot + 45_000 // call B rang well after A's clock started
        killProcess()
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 60_000, boot + 60_000)
        assertEquals(0L, SharedStatusNotice.liveElapsedMs(60_000))
    }

    @Test
    fun `the same call that rang just before capture started still restores`() {
        store.callBeganAt = boot - 3_000
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 0, boot)
        killProcess()
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 60_000, boot + 60_000)
        assertEquals(60_000L, SharedStatusNotice.liveElapsedMs(60_000))
    }

    @Test
    fun `a failing store never throws out of setLive`() {
        SharedStatusNotice.clockStore = object : SharedStatusNotice.ClockStore {
            override fun load(): SharedStatusNotice.SavedClock? = throw IllegalStateException("boom")
            override fun save(clock: SharedStatusNotice.SavedClock?) = throw IllegalStateException("boom")
            override fun lastNoCallAt() = 0L
            override fun liveCallBeganAt() = 0L
        }
        SharedStatusNotice.setLive(o, SharedStatusNotice.Live.RECORDING, 0, boot)
        assertEquals(SharedStatusNotice.Live.RECORDING, SharedStatusNotice.live)
    }
}
