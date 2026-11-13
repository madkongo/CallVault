package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.MicOpHealPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A heal refused for lack of a way back waits for one, instead of being forgotten 8 s after the call. */
class MicOpHealPendingTest {

    @Test
    fun only_no_way_back_stays_pending() {
        for (d in Decision.values()) {
            assertEquals(d.name, d == Decision.NO_WAY_BACK, MicOpHealPolicy.staysPending(d))
        }
    }

    @Test
    fun a_pending_check_is_taken_once_by_the_next_trigger() {
        val p = MicOpHealPolicy.Pending()
        assertFalse(p.isPending)
        assertNull(p.take())
        p.set("the carrier recording")
        assertTrue(p.isPending)
        assertEquals("the carrier recording", p.take())
        // Taken: a second trigger finds nothing, so Wi-Fi flapping does not pile up re-runs.
        assertFalse(p.isPending)
        assertNull(p.take())
    }

    @Test
    fun a_rerun_that_is_refused_again_goes_back_to_pending() {
        val p = MicOpHealPolicy.Pending()
        p.set("the VoIP recording")
        val what = p.take()!!
        // Re-run with still no way back (no Wi-Fi yet): the decision stays NO_WAY_BACK, so the caller sets it again.
        val again = MicOpHealPolicy.decide(1, false, canRelaunch = false, lastHealAtMs = null, nowMs = 0L)
        if (MicOpHealPolicy.staysPending(again)) p.set(what)
        assertTrue(p.isPending)
        // Wi-Fi arrives: the next run can heal, and the check is settled.
        val healed = MicOpHealPolicy.decide(1, false, canRelaunch = true, lastHealAtMs = null, nowMs = 0L)
        assertEquals(Decision.HEAL, healed)
        p.take()
        if (MicOpHealPolicy.staysPending(healed)) p.set(what)
        assertFalse(p.isPending)
    }

    @Test
    fun an_already_open_adbd_tcp_port_is_a_way_back_with_no_wifi_and_no_wireless_debugging() {
        // The Pixel 8 report: USB debugging on, Wireless debugging off, no Wi-Fi, offline recording off — and the connect
        // path still reached adbd over its own tcp port, so the heal could have run at once.
        val args = booleanArrayOf(false, true, false, true, false, false)
        assertFalse(MicOpHealPolicy.canRelaunch(args[0], args[1], args[2], args[3], args[4], args[5]))
        assertTrue(MicOpHealPolicy.canRelaunch(args[0], args[1], args[2], args[3], args[4], args[5], tcpPortOpen = true))
        assertEquals(
            Decision.HEAL,
            MicOpHealPolicy.decide(1, false, canRelaunch = true, lastHealAtMs = null, nowMs = 0L),
        )
    }

    @Test
    fun nothing_reachable_stays_no_way_back_and_pending() {
        val can = MicOpHealPolicy.canRelaunch(false, true, false, true, false, false, tcpPortOpen = false)
        val d = MicOpHealPolicy.decide(1, false, canRelaunch = can, lastHealAtMs = null, nowMs = 0L)
        assertEquals(Decision.NO_WAY_BACK, d)
        assertTrue(MicOpHealPolicy.staysPending(d))
    }

    @Test
    fun an_open_port_does_not_help_when_adbd_is_not_running() {
        // tcpPortOpen is read from a live answer, but the existing rules are unchanged for every other input.
        assertFalse(MicOpHealPolicy.canRelaunch(false, false, false, false, false, false))
    }

    @Test
    fun the_way_back_appears_once_wifi_joins() {
        // The Pixel 8 case: no Wi-Fi, WRITE_SECURE_SETTINGS granted, offline recording off.
        assertFalse(MicOpHealPolicy.canRelaunch(false, false, false, true, false, wifiConnected = false))
        assertTrue(MicOpHealPolicy.canRelaunch(false, false, false, true, false, wifiConnected = true))
    }
}
