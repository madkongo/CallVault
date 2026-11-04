package com.baba.callvault.ui.screens

import com.baba.callvault.data.clips.ClipRange
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipViewTest {

    @Test fun `a short call is shown whole`() =
        assertEquals(ClipView(0, 120_000), ClipView.around(ClipRange(10_000, 20_000), 120_000))

    @Test fun `a long call zooms to the part with as much again on each side`() =
        assertEquals(ClipView(1_365_000, 1_455_000), ClipView.around(ClipRange(1_395_000, 1_425_000), 3_465_000))

    @Test fun `the view is at least a minute`() =
        assertEquals(ClipView(1_380_000, 1_440_000), ClipView.around(ClipRange(1_405_000, 1_415_000), 3_465_000))

    @Test fun `near the start the view stays inside the call`() =
        assertEquals(ClipView(0, 90_000), ClipView.around(ClipRange(0, 30_000), 3_465_000))

    @Test fun `the whole call selected shows the whole call`() =
        assertEquals(ClipView(0, 3_465_000), ClipView.around(ClipRange(0, 3_465_000), 3_465_000))
}
