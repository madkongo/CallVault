/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the geometry the native drain is driven with. Two things are load-bearing here: the ring wraps
 * at a power of two (getting this wrong produced periodic audio gaps), and every delivered value is
 * bounds-checked before it becomes a native offset.
 */
class HandoffGeometryTest {

    // A realistic 48 kHz stereo delivery: non-power-of-two frame count, comfortably sized ashmem.
    private fun realistic(
        frameCount: Int = 3840,
        sampleRate: Int = 48_000,
        channels: Int = 2,
        cblkSize: Int = 64 * 1024,
    ) = HandoffGeometry(frameCount, sampleRate, channels, cblkSize)

    // --- frame size ---

    @Test
    fun `frame size is two bytes per channel for PCM-16`() {
        assertEquals(2, realistic(channels = 1).frameSize)
        assertEquals(4, realistic(channels = 2).frameSize)
    }

    // --- ring wrap ---

    @Test
    fun `ring wraps at the next power of two above a non-power-of-two frame count`() {
        // The bug this guards: wrapping at 3840 reads the untouched 3840..4096 region every lap.
        assertEquals(4096, realistic(frameCount = 3840).wrapFrames)
        assertEquals(8192, realistic(frameCount = 4097).wrapFrames)
    }

    @Test
    fun `ring wraps at the frame count itself when it is already a power of two`() {
        assertEquals(2048, realistic(frameCount = 2048).wrapFrames)
        assertEquals(1, realistic(frameCount = 1).wrapFrames)
    }

    @Test
    fun `ring end offset accounts for the data offset and the rounded-up ring`() {
        // Arrange
        val geometry = realistic(frameCount = 3840, channels = 2)

        // Act
        val end = geometry.ringEndOffset

        // Assert
        assertEquals(geometry.dataOff + 4096L * 4, end)
    }

    // --- validation: accepted ---

    @Test
    fun `accepts a realistic delivery`() {
        assertNull(realistic().validationError())
    }

    @Test
    fun `accepts a ring that exactly fills the ashmem region`() {
        // Arrange: size the region to the last byte the drain will touch.
        val exact = HandoffGeometry.DATA_OFF + 4096 * 4

        // Act + Assert
        assertNull(realistic(frameCount = 4096, channels = 2, cblkSize = exact).validationError())
    }

    // --- validation: rejected ---

    @Test
    fun `a region one byte short clamps the ring instead of refusing it`() {
        // This used to be refused, and refusing it is what stopped Resilient recording working on a
        // Galaxy S24 FE. The reported count is not the ring's physical size, so the honest response is
        // to believe the mapping and clamp — the smaller power of two — rather than throw the delivery
        // away. What must never happen is reading past the end, asserted here directly.
        // Sized off the geometry's own dataOff: under plain JUnit Build.VERSION.SDK_INT is 0, so the
        // offset is the Android 11 value, and hardcoding 232 here quietly changes what is being tested.
        val oneByteShort = realistic().dataOff + 4096 * 4 - 1
        val geometry = realistic(frameCount = 4096, channels = 2, cblkSize = oneByteShort)

        assertNull("a clamped ring is usable, not an error", geometry.validationError())
        assertEquals(2048, geometry.wrapFrames)
        assertTrue("the ring must stay inside the mapping", geometry.ringEndOffset <= oneByteShort)
    }

    @Test
    fun `a rounded-up count that overflows is clamped to what the region holds`() {
        // frameCount 2049 rounds up to 4096 frames; the region was only sized for the unrounded 2049,
        // so the usable ring is 2048 — and the read stays inside the mapping.
        val sizedForUnrounded = realistic().dataOff + 2049 * 4
        val geometry = realistic(frameCount = 2049, channels = 2, cblkSize = sizedForUnrounded)

        assertEquals(2048, geometry.wrapFrames)
        assertTrue(geometry.ringEndOffset <= sizedForUnrounded)
    }

    @Test
    fun `rejects channel counts outside mono and stereo`() {
        assertNotNull(realistic(channels = 0).validationError())
        assertNotNull(realistic(channels = 3).validationError())
        assertNotNull(realistic(channels = -1).validationError())
    }

    @Test
    fun `rejects sample rates outside the supported range`() {
        assertNotNull(realistic(sampleRate = 0).validationError())
        assertNotNull(realistic(sampleRate = 7_999).validationError())
        assertNotNull(realistic(sampleRate = 192_001).validationError())
    }

    @Test
    fun `accepts sample rates at the range boundaries`() {
        assertNull(realistic(sampleRate = 8_000).validationError())
        assertNull(realistic(sampleRate = 192_000).validationError())
    }

    @Test
    fun `rejects an unsized or unreadable ashmem region`() {
        assertNotNull(realistic(cblkSize = 0).validationError())
        assertNotNull(realistic(cblkSize = -1).validationError())
    }

    @Test
    fun `rejects a non-positive frame count`() {
        assertNotNull(HandoffGeometry(0, 48_000, 2, 64 * 1024).validationError())
        assertNotNull(HandoffGeometry(-8, 48_000, 2, 64 * 1024).validationError())
    }

    // --- construction ---

    @Test
    fun `substitutes the fallback when the daemon reported no frame count`() {
        // Arrange + Act
        val geometry = HandoffGeometry.of(frameCount = 0, sampleRate = 48_000, channels = 1, cblkSize = 64 * 1024)

        // Assert
        assertEquals(HandoffGeometry.FRAME_COUNT_FALLBACK, geometry.frameCount)
        assertNull(geometry.validationError())
    }

    @Test
    fun `keeps a reported frame count`() {
        assertEquals(
            3840,
            HandoffGeometry.of(frameCount = 3840, sampleRate = 48_000, channels = 2, cblkSize = 64 * 1024).frameCount,
        )
    }

    // --- the ring is bounded by the MAPPING, not by what the daemon reported ---

    @Test
    fun `ring is capped by what the shared memory can actually hold`() {
        // The Galaxy S24 FE case: getBufferSizeInFrames() reported a count rounding to 8192 frames
        // while AudioFlinger had allocated room for 4096. Trusting the report would read 12 KB past
        // the end of the mapping; AudioFlinger allocates roundup(frameCount)*frameSize right after the
        // control block, so the region's own size is the ground truth.
        val g = HandoffGeometry(frameCount = 8192, sampleRate = 48_000, channels = 2, cblkSize = 20480)

        assertEquals(4096, g.wrapFrames)
        assertNull("a ring that fits must not be rejected", g.validationError())
        assertTrue("the ring must end inside the mapping", g.ringEndOffset <= 20480)
    }

    @Test
    fun `an honest report is left alone`() {
        // Where the two agree — every OnePlus 12 delivery so far — nothing changes.
        val g = realistic(frameCount = 3840, cblkSize = 64 * 1024)
        assertEquals(4096, g.wrapFrames)
        assertNull(g.validationError())
    }

    @Test
    fun `never exceeds the reported count even when the mapping is huge`() {
        // A roomy mapping must not inflate the ring past what the server actually uses.
        val g = HandoffGeometry(frameCount = 2048, sampleRate = 48_000, channels = 2, cblkSize = 1 shl 20)
        assertEquals(2048, g.wrapFrames)
    }

    @Test
    fun `rejects a mapping with no usable ring`() {
        // What a FAST record track looks like: its PCM lives in a separate pipe, so there is nothing
        // usable after the control block and the offsets would point at whatever follows.
        val g = HandoffGeometry(frameCount = 8192, sampleRate = 48_000, channels = 2, cblkSize = 300)
        assertNotNull(g.validationError())
    }

    @Test
    fun `the ring offset matches the control block size for this android version`() {
        // 224 on Android 11, 228 on 12, 232 on 13+. minSdk is 30, so a hardcoded 232 was wrong on the
        // two oldest supported releases — it would read the ring misaligned rather than fail loudly.
        val expected = when {
            android.os.Build.VERSION.SDK_INT >= 33 -> 232
            android.os.Build.VERSION.SDK_INT >= 31 -> 228
            else -> 224
        }
        assertEquals(expected, realistic().dataOff)
    }
}
