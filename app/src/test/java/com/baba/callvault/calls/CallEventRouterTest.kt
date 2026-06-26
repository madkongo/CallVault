package com.baba.callvault.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallEventRouterTest {
    private fun event() = CallEvent(CallEvent.Phase.ACTIVE, "1", CallDirection.INCOMING, false)

    @Test fun forwards_events_from_active_source_only() {
        val seen = mutableListOf<CallEvent>()
        val router = CallEventRouter(sink = { seen.add(it) })
        router.setActiveMode(DetectionMode.TELECOM)

        router.submit(DetectionMode.TELECOM, event())   // active → forwarded
        router.submit(DetectionMode.BROADCAST, event())  // inactive → dropped

        assertEquals(1, seen.size)
    }

    @Test fun switching_mode_changes_which_source_is_forwarded() {
        val seen = mutableListOf<CallEvent>()
        val router = CallEventRouter(sink = { seen.add(it) })
        router.setActiveMode(DetectionMode.BROADCAST)
        router.submit(DetectionMode.BROADCAST, event())
        router.setActiveMode(DetectionMode.TELECOM)
        router.submit(DetectionMode.BROADCAST, event())  // now inactive → dropped
        assertEquals(1, seen.size)
    }
}
