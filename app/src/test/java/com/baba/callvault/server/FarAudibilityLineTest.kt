package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarAudibilityLineTest {

    @Test
    fun `a far party that was heard needs no line`() {
        assertNull(FarAudibilityLine.atEnd(farPartyHeard = true, encodedFrames = 48_000, pauseSeen = false, framesAfterResume = 0))
    }

    @Test
    fun `no pause and a silent far party is a warning`() {
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 48_000, pauseSeen = false, framesAfterResume = 0)!!
        assertTrue(line.warn)
        assertEquals(FarAudibilityLine.NEVER_AUDIBLE, line.text)
    }

    @Test
    fun `held, then answered, with the far party silent after the resume is a warning`() {
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 96_000, pauseSeen = true, framesAfterResume = 48_000)!!
        assertTrue(line.warn)
        assertEquals(FarAudibilityLine.NEVER_AUDIBLE, line.text)
    }

    @Test
    fun `held for the whole call after a short slice before the hold is not blamed on the app or the OEM`() {
        // OP9 2026-11-20: an unanswered held WhatsApp call encoded ~434 ms before the hold paused the capture.
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 20_832, pauseSeen = true, framesAfterResume = 0)!!
        assertFalse(line.warn)
        assertEquals(FarAudibilityLine.HELD_TO_THE_END, line.text)
    }

    @Test
    fun `a capture held for the whole call is not blamed on the app or the OEM`() {
        // N20: an outgoing call held until answered, and never answered.
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 0, pauseSeen = true, framesAfterResume = 0)!!
        assertFalse(line.warn)
        assertEquals(FarAudibilityLine.HELD_TO_THE_END, line.text)
    }

    @Test
    fun `nothing recorded with no pause is still a warning, and does not claim a pause`() {
        // Feeders ending early, or no slot ever anchored: a real failure, not a hold.
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 0, pauseSeen = false, framesAfterResume = 0)!!
        assertTrue(line.warn)
        assertEquals(FarAudibilityLine.NOTHING_RECORDED, line.text)
        assertFalse(line.text, line.text.contains("paus"))
    }
}
