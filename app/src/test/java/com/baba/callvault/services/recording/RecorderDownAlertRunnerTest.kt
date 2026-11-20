package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R1: the wiring around the policy: the master-switch lookup, POST/CLEAR dispatch, logging, and the service stopping. */
class RecorderDownAlertRunnerTest {


    private class Rig {
        var now = 0L
        var switchedOff = false
        var postResult = true
        val posted = mutableListOf<String>()
        var cleared = 0
        var causeAsked = 0
        val log = mutableListOf<String>()

        val runner = RecorderDownAlertRunner(
            isSwitchedOff = { switchedOff },
            nowMs = { now },
            describeCause = {
                causeAsked++
                RecorderDownAlertRunner.Cause("NEEDS_WIFI", "Join a Wi-Fi network", shizukuMode = false)
            },
            post = { posted += it; postResult },
            clear = { cleared++ },
            alwaysOnLog = { log += it },
        )

        fun tick(alive: Boolean) { runner.onTick(alive); now += 60_000L }
        fun downTicks(n: Int) = repeat(n) { tick(false) }
    }

    @Test
    fun `a lasting outage posts the reason once and logs it with the cause`() {
        val rig = Rig()
        rig.downTicks(10)
        assertEquals(listOf("Join a Wi-Fi network"), rig.posted)
        assertEquals(1, rig.log.size)
        assertTrue(rig.log[0], rig.log[0].startsWith("recorder-down alert SHOWN cause=NEEDS_WIFI"))
        assertTrue(rig.log[0], rig.log[0].endsWith("posted=true"))
    }

    @Test
    fun `the cause is not worked out unless an alert is actually due`() {
        val rig = Rig()
        rig.downTicks(2)
        assertEquals(0, rig.causeAsked)
    }

    @Test
    fun `a notification that could not be shown is logged as not posted`() {
        val rig = Rig().apply { postResult = false }
        rig.downTicks(5)
        assertTrue(rig.log[0], rig.log[0].endsWith("posted=false"))
    }

    @Test
    fun `recording switched off means no alert however long the recorder is down`() {
        val rig = Rig().apply { switchedOff = true }
        rig.downTicks(20)
        assertTrue(rig.posted.isEmpty())
        assertTrue(rig.log.isEmpty())
    }

    @Test
    fun `the user switching recording off takes a standing alert down and says why`() {
        val rig = Rig()
        rig.downTicks(5)
        rig.switchedOff = true
        rig.tick(false)
        assertEquals(1, rig.cleared)
        assertEquals("recorder-down alert CLEARED reason=recording switched off", rig.log.last())
    }

    @Test
    fun `the recorder coming back clears the alert and logs it`() {
        val rig = Rig()
        rig.downTicks(5)
        rig.tick(true)
        rig.tick(true)
        assertEquals(1, rig.cleared)
        assertEquals("recorder-down alert CLEARED reason=recorder ready again", rig.log.last())
    }

    @Test
    fun `the service stopping while the alert is up clears it and logs it`() {
        val rig = Rig()
        rig.downTicks(5)
        rig.runner.onServiceStopping("stood down for Shizuku mode")
        assertEquals(1, rig.cleared)
        assertTrue(rig.log.last(), rig.log.last().contains("keep-alive stopping (stood down for Shizuku mode)"))
    }

    @Test
    fun `the service stopping with no alert up clears nothing`() {
        val rig = Rig()
        rig.downTicks(2)
        rig.runner.onServiceStopping("service destroyed")
        assertEquals(0, rig.cleared)
        assertTrue(rig.log.isEmpty())
    }

    @Test
    fun `a stopped service does not clear twice and a later outage can alert again`() {
        val rig = Rig()
        rig.downTicks(5)
        rig.runner.onServiceStopping("service destroyed")
        rig.tick(true)
        assertEquals(1, rig.cleared)
        rig.downTicks(5)
        assertEquals(2, rig.posted.size)
    }
}
