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
    fun the_way_back_appears_once_wifi_joins() {
        // The Pixel 8 case: no Wi-Fi, WRITE_SECURE_SETTINGS granted, offline recording off.
        assertFalse(MicOpHealPolicy.canRelaunch(false, false, false, true, false, wifiConnected = false))
        assertTrue(MicOpHealPolicy.canRelaunch(false, false, false, true, false, wifiConnected = true))
    }
}
