package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.RecorderDownAlertPolicy.Action
import com.baba.callvault.services.recording.RecorderDownAlertPolicy.State
import com.baba.callvault.services.recording.RecorderDownAlertPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R1: a recorder that stays down must be announced once, loudly, and never for a blip. */
class RecorderDownAlertPolicyTest {

    private val minute = 60_000L

    /** Feeds one reading per minute starting at t=0 and returns every step. */
    private fun run(readings: List<Boolean>, off: Boolean = false, start: State = State()): List<Step> {
        var state = start
        return readings.mapIndexed { i, alive ->
            RecorderDownAlertPolicy.step(state, alive, off, i * minute).also { state = it.state }
        }
    }

    private fun actions(steps: List<Step>) = steps.map { it.action }

    @Test
    fun `a recorder that stays down alerts once after the grace period`() {
        val steps = run(List(10) { false })
        assertEquals(1, actions(steps).count { it == Action.POST })
        // ticks at t=0,1,2 min: the third read is 2 minutes in, still inside the 3 minute grace
        assertEquals(Action.NONE, steps[2].action)
        assertEquals(Action.POST, steps[3].action)
    }

    @Test
    fun `a relaunch that comes back within a couple of ticks never alerts`() {
        val steps = run(listOf(false, false, true, false, false, true))
        assertFalse(actions(steps).contains(Action.POST))
    }

    @Test
    fun `a long outage does not repeat the alert every minute`() {
        val steps = run(List(60) { false })
        assertEquals(1, actions(steps).count { it == Action.POST })
    }

    @Test
    fun `the alert is cleared when the recorder is ready again, and only then`() {
        val steps = run(List(5) { false } + listOf(true, true))
        assertEquals(Action.CLEAR, steps[5].action)
        assertEquals(Action.NONE, steps[6].action)
        assertEquals(1, actions(steps).count { it == Action.CLEAR })
    }

    @Test
    fun `a second outage alerts again`() {
        val steps = run(List(5) { false } + listOf(true) + List(5) { false })
        assertEquals(2, actions(steps).count { it == Action.POST })
    }

    @Test
    fun `no alert while the user has switched recording off`() {
        val steps = run(List(20) { false }, off = true)
        assertTrue(actions(steps).all { it == Action.NONE })
    }

    @Test
    fun `switching recording off during an alert takes the alert down`() {
        val raised = run(List(5) { false })
        val step = RecorderDownAlertPolicy.step(raised.last().state, alive = false, recordingSwitchedOff = true, nowMs = 6 * minute)
        assertEquals(Action.CLEAR, step.action)
        assertEquals(State(), step.state)
    }

    @Test
    fun `a long sleep between ticks does not alert on the first tick after waking`() {
        // Down at t=0, the phone dozes, the next tick is an hour later: one read, not three.
        val first = RecorderDownAlertPolicy.step(State(), alive = false, recordingSwitchedOff = false, nowMs = 0)
        val second = RecorderDownAlertPolicy.step(first.state, alive = false, recordingSwitchedOff = false, nowMs = 60 * minute)
        assertEquals(Action.NONE, second.action)
    }
}
