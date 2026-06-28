package com.baba.callvault.ui.dialer

import com.baba.callvault.calls.CallDirection
import com.baba.callvault.calls.CallEvent
import com.baba.callvault.calls.UiCall
import org.junit.Assert.assertEquals
import org.junit.Test

class InCallViewModelTest {
    @Test fun shows_unknown_when_number_null() {
        assertEquals("Unknown", InCallLabels.displayNumber(UiCall(null, CallEvent.Phase.RINGING, CallDirection.INCOMING, false, false), unknown = "Unknown"))
    }
    @Test fun shows_number_when_present() {
        assertEquals("+15551234", InCallLabels.displayNumber(UiCall("+15551234", CallEvent.Phase.ACTIVE, CallDirection.OUTGOING, false, false), unknown = "Unknown"))
    }
}
