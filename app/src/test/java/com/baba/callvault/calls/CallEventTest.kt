package com.baba.callvault.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEventTest {
    @Test fun builds_incoming_ringing_event() {
        val e = CallEvent(CallEvent.Phase.RINGING, "+15551234", CallDirection.INCOMING, isEmergency = false)
        assertEquals(CallEvent.Phase.RINGING, e.phase)
        assertEquals(CallDirection.INCOMING, e.direction)
        assertEquals("+15551234", e.number)
        assertTrue(!e.isEmergency)
    }

    @Test fun emergency_flag_is_carried() {
        val e = CallEvent(CallEvent.Phase.DIALING, "911", CallDirection.OUTGOING, isEmergency = true)
        assertTrue(e.isEmergency)
    }
}
