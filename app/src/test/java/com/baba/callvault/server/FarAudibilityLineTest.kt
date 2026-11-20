package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarAudibilityLineTest {

    @Test
    fun `a far party that was heard needs no line`() {
        assertNull(FarAudibilityLine.atEnd(farPartyHeard = true, encodedFrames = 48_000, pauseSeen = false))
    }

    @Test
    fun `a far party never heard in recorded audio is a warning, paused or not`() {
        listOf(false, true).forEach { paused ->
            val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 48_000, pauseSeen = paused)!!
            assertTrue(line.warn)
            assertEquals(FarAudibilityLine.NEVER_AUDIBLE, line.text)
        }
    }

    @Test
    fun `a capture held for the whole call is not blamed on the app or the OEM`() {
        // N20: an outgoing call held until answered, and never answered.
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 0, pauseSeen = true)!!
        assertFalse(line.warn)
        assertEquals(FarAudibilityLine.NOTHING_RECORDED_WHILE_HELD, line.text)
    }

    @Test
    fun `nothing recorded with no pause is still a warning, and does not claim a pause`() {
        // Feeders ending early, or no slot ever anchored: a real failure, not a hold.
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 0, pauseSeen = false)!!
        assertTrue(line.warn)
        assertEquals(FarAudibilityLine.NOTHING_RECORDED, line.text)
        assertFalse(line.text, line.text.contains("paus"))
    }
}
