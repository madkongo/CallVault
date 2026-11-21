package com.baba.callvault.transcription

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** C82: a range decode must start at or before the point it asked for. */
class SeekAtOrBeforeTest {

    /** A track whose seek lands [lateByUs] after the point asked for (never before 0), or fails with -1. */
    private class FakeTrack(private val lateByUs: Long, private val failsBelow: Long = -1L) : SeekableTrack {
        val seeks = mutableListOf<Long>()
        override var sampleTimeUs: Long = 0L
        override fun seekTo(timeUs: Long) {
            seeks += timeUs
            sampleTimeUs = if (timeUs < failsBelow) -1L else timeUs + lateByUs
        }
    }

    @Test
    fun `an exact seek takes the single seek it always did`() {
        val track = FakeTrack(lateByUs = 0L)
        assertEquals(100_000_000L, SeekAtOrBefore.seek(track, 100_000_000L))
        assertEquals(listOf(100_000_000L), track.seeks)
    }

    @Test
    fun `a seek landing early is accepted at once`() {
        val track = FakeTrack(lateByUs = -300_000L)
        SeekAtOrBefore.seek(track, 100_000_000L)
        assertEquals(1, track.seeks.size)
    }

    @Test
    fun `a seek landing late steps back until the start is not after the target`() {
        val track = FakeTrack(lateByUs = 12_000_000L)
        val landed = SeekAtOrBefore.seek(track, 100_000_000L)
        assert(landed <= 100_000_000L) { "landed $landed" }
        // 0, then 5 s back, then 10 s back, then 20 s back
        assertEquals(listOf(100_000_000L, 95_000_000L, 90_000_000L, 80_000_000L), track.seeks)
    }

    @Test
    fun `a target near the start stops at zero`() {
        val track = FakeTrack(lateByUs = 3_000_000L)
        val landed = SeekAtOrBefore.seek(track, 1_000_000L)
        assertEquals(3_000_000L, landed) // the earliest sample there is
        assertEquals(0L, track.seeks.last())
    }

    @Test
    fun `no sample even at the start fails loudly instead of looping`() {
        val track = FakeTrack(lateByUs = 0L, failsBelow = Long.MAX_VALUE)
        try {
            SeekAtOrBefore.seek(track, 100_000_000L)
            fail("expected a failure")
        } catch (e: IOException) {
            assert(track.seeks.size < 20)
        }
    }

    @Test
    fun `a seek that finds nothing steps back to a real sample`() {
        val far = object : SeekableTrack {
            var current = 0L
            override val sampleTimeUs get() = current
            override fun seekTo(timeUs: Long) { current = if (timeUs > 60_000_000L) -1L else timeUs }
        }
        assertEquals(60_000_000L, SeekAtOrBefore.seek(far, 100_000_000L))
    }
}
