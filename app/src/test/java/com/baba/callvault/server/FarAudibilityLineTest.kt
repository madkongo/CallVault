package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarAudibilityLineTest {

    @Test
    fun `a far party that was heard needs no line`() {
        assertNull(FarAudibilityLine.atEnd(farPartyHeard = true, encodedFrames = 48_000))
    }

    @Test
    fun `a far party never heard in recorded audio is a warning`() {
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 48_000)!!
        assertTrue(line.warn)
        assertTrue(line.text, line.text.startsWith("Far party was never audible"))
    }

    @Test
    fun `a capture paused for the whole call is not blamed on the app or the OEM`() {
        // N20: an outgoing call held until answered, and never answered.
        val line = FarAudibilityLine.atEnd(farPartyHeard = false, encodedFrames = 0)!!
        assertFalse(line.warn)
        assertFalse(line.text, line.text.contains("never audible"))
        assertEquals(FarAudibilityLine.NOTHING_RECORDED, line.text)
    }
}
