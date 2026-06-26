package com.baba.callvault.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallDetectionSinkTest {
    @Test fun broadcast_source_reaches_sink_in_broadcast_mode() {
        val seen = mutableListOf<CallEvent>()
        val router = CallEventRouter(sink = { seen.add(it) })
        router.setActiveMode(DetectionMode.BROADCAST)
        val src = com.baba.callvault.dialer.BroadcastCallEventSource(router)
        src.emit(CallEvent(CallEvent.Phase.ACTIVE, "1", CallDirection.UNKNOWN, false))
        assertEquals(1, seen.size)
    }
}
