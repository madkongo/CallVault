package com.baba.callvault.services.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** UI-1: Home follows the real recording state through [SharedStatusNotice.liveFlow], independent of notification claims. */
class SharedStatusNoticeLiveFlowTest {

    @After
    fun reset() = SharedStatusNotice.resetForTest()

    private val carrier = SharedStatusNotice.Owner.CARRIER
    private val voip = SharedStatusNotice.Owner.VOIP

    @Test
    fun `nothing is live at first`() = assertEquals(SharedStatusNotice.Live.NONE, SharedStatusNotice.live)

    @Test
    fun `recording then paused then ended`() {
        SharedStatusNotice.setLive(carrier, SharedStatusNotice.Live.RECORDING)
        assertEquals(SharedStatusNotice.Live.RECORDING, SharedStatusNotice.liveFlow.value)
        SharedStatusNotice.setLive(carrier, SharedStatusNotice.Live.PAUSED)
        assertEquals(SharedStatusNotice.Live.PAUSED, SharedStatusNotice.live)
        SharedStatusNotice.setLive(carrier, SharedStatusNotice.Live.NONE)
        assertEquals(SharedStatusNotice.Live.NONE, SharedStatusNotice.live)
    }

    @Test
    fun `an app call needs no notification claim to count`() {
        SharedStatusNotice.setLive(voip, SharedStatusNotice.Live.RECORDING)
        assertEquals(SharedStatusNotice.Live.RECORDING, SharedStatusNotice.live)
    }

    @Test
    fun `two overlapping calls keep it recording until both end, and recording wins over paused`() {
        SharedStatusNotice.setLive(carrier, SharedStatusNotice.Live.PAUSED)
        SharedStatusNotice.setLive(voip, SharedStatusNotice.Live.RECORDING)
        assertEquals(SharedStatusNotice.Live.RECORDING, SharedStatusNotice.live)
        SharedStatusNotice.setLive(voip, SharedStatusNotice.Live.NONE)
        assertEquals(SharedStatusNotice.Live.PAUSED, SharedStatusNotice.live)
    }

    @Test
    fun `reset clears it`() {
        SharedStatusNotice.setLive(carrier, SharedStatusNotice.Live.RECORDING)
        SharedStatusNotice.resetForTest()
        assertEquals(SharedStatusNotice.Live.NONE, SharedStatusNotice.live)
    }

    @Test
    fun `an active carrier session is recording or paused`() {
        assertEquals(SharedStatusNotice.Live.RECORDING, SharedStatusNotice.carrierLive(true, false, false))
        assertEquals(SharedStatusNotice.Live.PAUSED, SharedStatusNotice.carrierLive(true, true, false))
    }

    @Test
    fun `a lost recorder or a capture that never started is not live, so the state setter cannot flip it back`() {
        assertEquals(SharedStatusNotice.Live.NONE, SharedStatusNotice.carrierLive(true, false, true))
        assertEquals(SharedStatusNotice.Live.NONE, SharedStatusNotice.carrierLive(true, true, true))
    }

    @Test
    fun `a session that is not active is not live`() =
        assertEquals(SharedStatusNotice.Live.NONE, SharedStatusNotice.carrierLive(false, false, false))
}
