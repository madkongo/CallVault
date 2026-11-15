package com.baba.callvault.services.recording.handoff

import com.baba.callvault.services.recording.handoff.HandoffEarlyEnd.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffEarlyEndTest {

    private val max = 3

    @Test
    fun `a drain that threw ends the recording and the track must be stopped`() {
        val o = HandoffEarlyEnd.decide(drainThrew = true, rearms = 0, maxRearms = max, stopRequested = false)
        assertEquals(Outcome.BUDGET_SPENT, o)
        assertTrue(o.endsRecording)
    }

    @Test
    fun `a spent budget ends the recording even if a stop is also pending`() {
        val o = HandoffEarlyEnd.decide(false, rearms = max, maxRearms = max, stopRequested = true)
        assertEquals(Outcome.BUDGET_SPENT, o)
        assertTrue(o.endsRecording)
    }

    @Test
    fun `with budget left an early end is rebuilt, not ended`() {
        val o = HandoffEarlyEnd.decide(false, rearms = max - 1, maxRearms = max, stopRequested = false)
        assertEquals(Outcome.REBUILD, o)
        assertFalse(o.endsRecording)
    }

    @Test
    fun `a stop between drains is a normal end that the normal path tears down`() {
        val o = HandoffEarlyEnd.decide(false, rearms = 0, maxRearms = max, stopRequested = true)
        assertEquals(Outcome.STOP_REQUESTED, o)
        assertFalse(o.endsRecording)
    }

    @Test
    fun `a failed rebuild ends the recording`() {
        assertTrue(Outcome.REBUILD_FAILED.endsRecording)
    }

    @Test
    fun `the always-on line carries reason, rebuild outcome, elapsed seconds and the stop result`() {
        val line = HandoffEarlyEnd.timelineLine("invalidated", Outcome.REBUILD_FAILED, 1, max, 3_040L, stopAccepted = true)
        assertTrue(line, "exit=invalidated" in line)
        assertTrue(line, "outcome=rebuild failed" in line)
        assertTrue(line, "rebuilds=1/$max" in line)
        assertTrue(line, "elapsed=3.0s" in line)
        assertTrue(line, "heldTrackStop=accepted" in line)
        assertTrue("refused" in HandoffEarlyEnd.timelineLine("x", Outcome.BUDGET_SPENT, 3, max, 0, false))
        assertTrue("none held" in HandoffEarlyEnd.timelineLine("x", Outcome.BUDGET_SPENT, 3, max, 0, null))
    }
}
