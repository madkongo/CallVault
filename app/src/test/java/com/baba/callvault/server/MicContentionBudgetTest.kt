package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicContentionBudgetTest {

    @Test
    fun `a second check within ten seconds is refused`() {
        val b = MicContentionBudget()
        assertTrue(b.tryBegin(nowMs = 0))
        b.end()
        assertFalse(b.tryBegin(nowMs = 9_999))
        assertTrue(b.tryBegin(nowMs = 10_000))
    }

    @Test
    fun `a check already running refuses another`() {
        val b = MicContentionBudget()
        assertTrue(b.tryBegin(0))
        assertFalse(b.tryBegin(60_000))
    }

    @Test
    fun `checks stop after the per call cap`() {
        val b = MicContentionBudget()
        var t = 0L
        repeat(MicContentionBudget.MAX_CHECKS) { assertTrue(b.tryBegin(t)); b.end(); t += 10_000 }
        assertFalse(b.tryBegin(t))
    }

    @Test
    fun `checks stop once evidence is recorded`() {
        val b = MicContentionBudget()
        assertTrue(b.tryBegin(0)); b.end()
        b.evidenceRecorded()
        assertFalse(b.tryBegin(20_000))
    }

    @Test
    fun `an unresolved uid is given up on after two failures`() {
        val b = MicContentionBudget()
        assertTrue(b.mayResolveUid()); b.uidResolved(false)
        assertTrue(b.mayResolveUid()); b.uidResolved(false)
        assertFalse(b.mayResolveUid())
    }

    @Test
    fun `a resolved uid keeps resolving allowed`() {
        val b = MicContentionBudget()
        b.uidResolved(false); b.uidResolved(true)
        assertTrue(b.mayResolveUid())
    }

    @Test
    fun `the state line is logged only when it changes`() {
        val b = MicContentionBudget()
        assertTrue(b.stateChanged("a")); assertFalse(b.stateChanged("a")); assertTrue(b.stateChanged("b"))
        assertEquals(false, b.stateChanged("b"))
    }
}
