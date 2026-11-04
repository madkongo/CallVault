package com.baba.callvault.data.clips

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipRangeTest {

    @Test fun `whole recording`() = assertEquals(ClipRange(0, 272_000), ClipRange.whole(272_000))

    @Test fun `from the playing position takes thirty seconds`() =
        assertEquals(ClipRange(115_000, 145_000), ClipRange.fromPosition(115_000, 272_000))

    @Test fun `near the end it slides back to keep thirty seconds`() =
        assertEquals(ClipRange(242_000, 272_000), ClipRange.fromPosition(265_000, 272_000))

    @Test fun `a short recording is selected whole`() =
        assertEquals(ClipRange(0, 12_000), ClipRange.fromPosition(5_000, 12_000))

    @Test fun `start cannot pass the end`() =
        assertEquals(ClipRange(59_000, 60_000), ClipRange(10_000, 60_000).withStart(70_000, 272_000))

    @Test fun `end cannot pass the start or the recording`() {
        assertEquals(ClipRange(10_000, 11_000), ClipRange(10_000, 60_000).withEnd(0, 272_000))
        assertEquals(ClipRange(10_000, 272_000), ClipRange(10_000, 60_000).withEnd(999_000, 272_000))
    }

    @Test fun `nonsense input stays inside the recording`() =
        assertEquals(ClipRange(0, 5_000), ClipRange.of(-50, 9_000_000, 5_000))

    @Test fun clock() {
        assertEquals("1:12", ClipRange.clock(72_000))
        assertEquals("0:05", ClipRange.clock(5_999))
        assertEquals("1:02:03", ClipRange.clock(3_723_000))
    }

    @Test fun `file name carries the range and no unsafe characters`() =
        assertEquals(
            "Dana_Levi (1-12 – 2-40).ogg",
            ClipNaming.fileName("Dana/Levi", ClipRange(72_000, 160_000), "ogg"),
        )

    @Test fun `a dot in a contact's name is kept`() =
        assertEquals("Dr. Cohen (0-06 – 0-14).ogg", ClipNaming.fileName("Dr. Cohen", ClipRange(6_000, 14_000), "ogg"))

    @Test fun `bidi isolation marks never reach a file name`() =
        assertEquals(
            "Dana (0-06 – 0-14).ogg",
            ClipNaming.fileName("\u2068Dana\u2069", ClipRange(6_000, 14_000), "ogg"),
        )
}
