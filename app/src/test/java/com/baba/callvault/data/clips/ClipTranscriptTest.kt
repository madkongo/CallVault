package com.baba.callvault.data.clips

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipTranscriptTest {

    private val lines = listOf(
        ClipLine(58_000, 71_000, "Hi, it's about the delivery."),
        ClipLine(70_000, 99_000, "The price was 4,200.", speaker = "Dana"),
        ClipLine(100_000, 140_000, "Two years warranty."),
        ClipLine(155_000, 175_000, "Send it in writing."),
        ClipLine(110_000, 111_000, "   "),
    )

    @Test fun `only lines whose middle is inside the clip`() {
        val kept = ClipTranscript.linesIn(lines, ClipRange(72_000, 160_000))
        assertEquals(listOf("The price was 4,200.", "Two years warranty."), kept.map { it.text })
    }

    @Test fun `renders with call times and speaker`() =
        assertEquals(
            "[1:10] Dana: The price was 4,200.\n[1:40] Two years warranty.",
            ClipTranscript.render(ClipTranscript.linesIn(lines, ClipRange(72_000, 160_000))),
        )

    @Test fun `lines from the clip file move onto the call's clock`() {
        // The clip file started 80 ms early (lead-in), at 71.92 s into the call.
        val fromClip = listOf(ClipLine(1_080, 20_080, "The price was 4,200."), ClipLine(60_000, 70_000, "after"))
        val moved = ClipTranscript.fromClipFile(fromClip, ClipRange(72_000, 100_000), leadInMs = 80)
        assertEquals(listOf(ClipLine(73_000, 92_000, "The price was 4,200.")), moved)
    }
}
