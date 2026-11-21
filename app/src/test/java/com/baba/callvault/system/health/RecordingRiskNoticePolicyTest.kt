package com.baba.callvault.system.health

import com.baba.callvault.system.health.RecordingRiskNoticePolicy.Decision
import com.baba.callvault.system.health.RecordingRiskNoticePolicy.State
import com.baba.callvault.system.health.RiskReason.APP_HIBERNATION
import com.baba.callvault.system.health.RiskReason.BACKGROUND_RESTRICTED
import com.baba.callvault.system.health.RiskReason.BATTERY_LIMITS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R5: when the background notice is posted, updated, cancelled or left alone. */
class RecordingRiskNoticePolicyTest {

    private val day = RecordingRiskNoticePolicy.DAY_MS
    private val now = 10 * day

    @Test
    fun `a first reason posts`() {
        val d = RecordingRiskNoticePolicy.decide(State(), now, listOf(BATTERY_LIMITS), recordingOff = false)
        assertEquals(Decision.Post(listOf(BATTERY_LIMITS), updating = false), d)
    }

    @Test
    fun `no reason cancels`() {
        val d = RecordingRiskNoticePolicy.decide(State(lastPostedSet = setOf(BATTERY_LIMITS)), now, emptyList(), recordingOff = false)
        assertTrue(d is Decision.Cancel)
    }

    @Test
    fun `recording switched off cancels even when reasons hold`() {
        val d = RecordingRiskNoticePolicy.decide(State(), now, listOf(BATTERY_LIMITS), recordingOff = true)
        assertTrue(d is Decision.Cancel)
    }

    @Test
    fun `the same reasons as last time are not posted again`() {
        val state = State(lastPostedAt = now - 5 * day, lastPostedSet = setOf(BATTERY_LIMITS, APP_HIBERNATION))
        val d = RecordingRiskNoticePolicy.decide(state, now, listOf(APP_HIBERNATION, BATTERY_LIMITS), recordingOff = false)
        assertTrue(d is Decision.Skip)
    }

    @Test
    fun `a changed set a day later updates the notice in place`() {
        val state = State(lastPostedAt = now - day, lastPostedSet = setOf(BATTERY_LIMITS))
        val d = RecordingRiskNoticePolicy.decide(state, now, listOf(BATTERY_LIMITS, BACKGROUND_RESTRICTED), recordingOff = false)
        assertEquals(Decision.Post(listOf(BATTERY_LIMITS, BACKGROUND_RESTRICTED), updating = true), d)
    }

    @Test
    fun `a changed set within a day of the last post waits`() {
        val state = State(lastPostedAt = now - day + 1, lastPostedSet = setOf(BATTERY_LIMITS))
        val d = RecordingRiskNoticePolicy.decide(state, now, listOf(BACKGROUND_RESTRICTED), recordingOff = false)
        assertTrue(d is Decision.Skip)
    }

    @Test
    fun `a clock that went backwards does not block a post for ever`() {
        val state = State(lastPostedAt = now + 3 * day, lastPostedSet = setOf(BATTERY_LIMITS))
        val d = RecordingRiskNoticePolicy.decide(state, now, listOf(APP_HIBERNATION), recordingOff = false)
        assertTrue(d is Decision.Post)
    }

    @Test
    fun `a check is due once a day, at boot, and never twice in a row on the tick`() {
        assertTrue(RecordingRiskNoticePolicy.isCheckDue(State(), now, atBoot = false))
        val checked = State(lastCheckAt = now - 1000)
        assertFalse(RecordingRiskNoticePolicy.isCheckDue(checked, now, atBoot = false))
        assertTrue(RecordingRiskNoticePolicy.isCheckDue(checked, now, atBoot = true))
        assertTrue(RecordingRiskNoticePolicy.isCheckDue(State(lastCheckAt = now - day), now, atBoot = false))
        assertTrue(RecordingRiskNoticePolicy.isCheckDue(State(lastCheckAt = now + day), now, atBoot = false))
    }

    @Test
    fun `state after a post remembers the set and the time`() {
        val after = RecordingRiskNoticePolicy.nextState(State(), Decision.Post(listOf(BATTERY_LIMITS), false), now)
        assertEquals(State(lastCheckAt = now, lastPostedAt = now, lastPostedSet = setOf(BATTERY_LIMITS)), after)
    }

    @Test
    fun `state after a cancel forgets the set so a return posts again`() {
        val before = State(lastCheckAt = 1, lastPostedAt = 5, lastPostedSet = setOf(BATTERY_LIMITS))
        val after = RecordingRiskNoticePolicy.nextState(before, Decision.Cancel("x"), now)
        assertEquals(State(lastCheckAt = now, lastPostedAt = 5, lastPostedSet = emptySet()), after)
    }

    @Test
    fun `state after a skip only records the check`() {
        val before = State(lastCheckAt = 1, lastPostedAt = 5, lastPostedSet = setOf(BATTERY_LIMITS))
        assertEquals(before.copy(lastCheckAt = now), RecordingRiskNoticePolicy.nextState(before, Decision.Skip("x"), now))
    }

    @Test
    fun `state round-trips through its stored form, and junk is ignored`() {
        val state = State(3, 4, setOf(APP_HIBERNATION, BATTERY_LIMITS))
        assertEquals(state.lastPostedSet, RecordingRiskNoticePolicy.parseSet(RecordingRiskNoticePolicy.formatSet(state.lastPostedSet)))
        assertEquals(setOf(BATTERY_LIMITS), RecordingRiskNoticePolicy.parseSet("BATTERY_LIMITS,NOPE,"))
        assertEquals(emptySet<RiskReason>(), RecordingRiskNoticePolicy.parseSet(null))
    }

    @Test
    fun `one reason is the text, several are a bullet list`() {
        assertEquals("Battery limits are on", RecordingRiskNoticePolicy.noticeText(listOf("Battery limits are on")))
        assertEquals("• A\n• B", RecordingRiskNoticePolicy.noticeText(listOf("A", "B")))
        assertEquals("• A\n• B\n• C", RecordingRiskNoticePolicy.noticeText(listOf("A", "B", "C")))
    }
}
