package com.baba.callvault.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallsViewSwipeTest {
    private val t = 100f

    @Test fun leftSwipeOnRecordingsOpensAllCalls() =
        assertEquals(true, CallsViewSwipe.target(callsView = false, dragX = -t - 1, thresholdPx = t, rtl = false))

    @Test fun rightSwipeOnAllCallsOpensRecordings() =
        assertEquals(false, CallsViewSwipe.target(callsView = true, dragX = t + 1, thresholdPx = t, rtl = false))

    @Test fun shortDragIsIgnored() {
        assertNull(CallsViewSwipe.target(false, -t + 1, t, false))
        assertNull(CallsViewSwipe.target(true, t - 1, t, false))
    }

    @Test fun swipeTowardsTheNonexistentEndStays() {
        assertNull(CallsViewSwipe.target(false, t + 1, t, false))
        assertNull(CallsViewSwipe.target(true, -t - 1, t, false))
    }

    @Test fun rightToLeftLayoutSwapsDirections() {
        assertEquals(true, CallsViewSwipe.target(false, t + 1, t, rtl = true))
        assertEquals(false, CallsViewSwipe.target(true, -t - 1, t, rtl = true))
        assertNull(CallsViewSwipe.target(false, -t - 1, t, rtl = true))
    }
}
