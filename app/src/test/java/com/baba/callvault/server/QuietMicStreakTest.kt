package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietMicStreakTest {

    private fun QuietMicStreak.feed(count: Int, allZero: Boolean, paused: Boolean): Int =
        (1..count).count { onChunk(allZero, paused) }

    @Test
    fun `a mic that reads zeros while not paused is re-taken after the limit`() {
        val streak = QuietMicStreak(limit = 15)
        assertEquals(0, streak.feed(14, allZero = true, paused = false))
        assertTrue(streak.onChunk(allZero = true, paused = false))
    }

    @Test
    fun `a paused capture reading only zeros never re-takes the mic`() {
        val streak = QuietMicStreak(limit = 15)
        // N20: an outgoing app call held until answered, 25 s of 20 ms chunks.
        assertEquals(0, streak.feed(1_250, allZero = true, paused = true))
    }

    @Test
    fun `a pause that ended with real audio gives the mic the full streak after resume`() {
        val streak = QuietMicStreak(limit = 15)
        streak.feed(10, allZero = true, paused = false)
        streak.feed(100, allZero = true, paused = true)
        streak.feed(1, allZero = false, paused = true)
        assertEquals(0, streak.feed(14, allZero = true, paused = false))
        assertTrue(streak.onChunk(allZero = true, paused = false))
    }

    @Test
    fun `a mic that went silent during the pause is re-taken on the first quiet chunk after resume`() {
        // The platform gave the mic to the calling app while held; waiting 15 more chunks would lose ~300 ms of
        // the user's voice right after the answer.
        val streak = QuietMicStreak(limit = 15)
        streak.feed(15, allZero = true, paused = true)
        assertTrue(streak.onChunk(allZero = true, paused = false))
        assertEquals(0, streak.feed(14, allZero = true, paused = false))
    }

    @Test
    fun `a mic silent through a short pause is not re-taken early`() {
        val streak = QuietMicStreak(limit = 15)
        streak.feed(14, allZero = true, paused = true)
        assertEquals(0, streak.feed(14, allZero = true, paused = false))
        assertTrue(streak.onChunk(allZero = true, paused = false))
    }

    @Test
    fun `a mic silent through the pause that has audio on resume is left alone`() {
        val streak = QuietMicStreak(limit = 15)
        streak.feed(500, allZero = true, paused = true)
        assertFalse(streak.onChunk(allZero = false, paused = false))
        assertEquals(0, streak.feed(14, allZero = true, paused = false))
    }

    @Test
    fun `real audio resets the streak, and a re-take starts it again`() {
        val streak = QuietMicStreak(limit = 15)
        streak.feed(14, allZero = true, paused = false)
        assertFalse(streak.onChunk(allZero = false, paused = false))
        assertEquals(0, streak.feed(14, allZero = true, paused = false))
        assertTrue(streak.onChunk(allZero = true, paused = false))
        assertEquals(0, streak.feed(14, allZero = true, paused = false))
    }
}
