/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder's accumulator.
 *
 * It exists because the previous one staged the whole call as bytes in a `ByteArrayOutputStream`,
 * then copied it with `toByteArray()`, then copied it again into a `ShortArray` — three full-length
 * buffers alive at overlapping moments, before a single sample reached the resampler.
 */
class PcmShortSinkTest {

    /** Little-endian PCM-16, the only layout MediaCodec produces here. */
    private fun bytesOf(vararg values: Short): ByteBuffer {
        val buf = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putShort(it) }
        buf.flip()
        return buf
    }

    @Test
    fun `samples come back in the order they went in`() {
        val sink = PcmShortSink(4)
        sink.append(bytesOf(1, -2, 3), 6)

        assertEquals(3, sink.size)
        assertEquals(1.toShort(), sink.array[0])
        assertEquals((-2).toShort(), sink.array[1])
        assertEquals(3.toShort(), sink.array[2])
    }

    @Test
    fun `little-endian byte pairs are decoded as signed shorts`() {
        val sink = PcmShortSink(2)
        // 0x8000 is the most negative short; a sign error here would read it as +32768 and wrap.
        sink.append(bytesOf(Short.MIN_VALUE, Short.MAX_VALUE), 4)

        assertEquals(Short.MIN_VALUE, sink.array[0])
        assertEquals(Short.MAX_VALUE, sink.array[1])
    }

    @Test
    fun `it grows past its initial capacity without losing anything`() {
        val sink = PcmShortSink(2)
        for (i in 0 until 100) sink.append(bytesOf(i.toShort()), 2)

        assertEquals(100, sink.size)
        for (i in 0 until 100) assertEquals(i.toShort(), sink.array[i])
    }

    @Test
    fun `a correctly pre-sized sink never reallocates`() {
        // The point of pre-sizing from the container's declared duration: no growth, so no moment
        // where two full-length buffers are alive at once.
        val sink = PcmShortSink(50)
        val before = sink.array
        for (i in 0 until 25) sink.append(bytesOf(i.toShort(), i.toShort()), 4)

        assertEquals(50, sink.size)
        assertTrue("pre-sized sink should not have reallocated", before === sink.array)
    }

    @Test
    fun `only the declared byte count is read, not the whole buffer`() {
        // MediaCodec hands back a buffer larger than the data in it; reading further would append
        // stale audio from the previous callback.
        val buf = bytesOf(7, 8, 9)
        val sink = PcmShortSink(4)
        sink.append(buf, 4)

        assertEquals(2, sink.size)
        assertEquals(7.toShort(), sink.array[0])
        assertEquals(8.toShort(), sink.array[1])
    }

    @Test
    fun `an odd byte count does not read half a sample`() {
        val sink = PcmShortSink(4)
        sink.append(bytesOf(5, 6), 3)

        assertEquals("a trailing half-sample is dropped", 1, sink.size)
        assertEquals(5.toShort(), sink.array[0])
    }

    @Test
    fun `a zero-length append is harmless`() {
        val sink = PcmShortSink(4)
        sink.append(bytesOf(1), 0)

        assertEquals(0, sink.size)
    }

    @Test
    fun `a zero or negative initial capacity still works`() {
        val sink = PcmShortSink(0)
        sink.append(bytesOf(42), 2)

        assertEquals(1, sink.size)
        assertEquals(42.toShort(), sink.array[0])
    }

    // --- spanUs: issue #63, a window must reserve its own length, not the whole call ---

    private val call45MinUs = 45L * 60 * 1_000_000

    @Test
    fun a_whole_file_decode_reserves_the_declared_length() {
        assertEquals(call45MinUs, PcmShortSink.spanUs(call45MinUs))
    }

    @Test
    fun a_five_minute_window_reserves_five_minutes_plus_seek_room_not_the_call() {
        val from = 10L * 60 * 1_000_000
        val to = 15L * 60 * 1_000_000
        assertEquals(5L * 60 * 1_000_000 + 2_000_000, PcmShortSink.spanUs(call45MinUs, from, to))
    }

    @Test
    fun the_first_window_needs_no_seek_room() {
        assertEquals(300_000_000L, PcmShortSink.spanUs(call45MinUs, 0L, 300_000_000L))
    }

    @Test
    fun a_window_past_the_end_is_cut_at_the_declared_length() {
        val from = 44L * 60 * 1_000_000
        assertEquals(60_000_000L + 2_000_000, PcmShortSink.spanUs(call45MinUs, from, call45MinUs + 300_000_000L))
    }

    @Test
    fun a_short_probe_reserves_only_the_probe() {
        assertEquals(2_000_000L, PcmShortSink.spanUs(call45MinUs, 0L, 2_000_000L))
    }

    @Test
    fun nothing_known_means_nothing_reserved_beyond_the_default() {
        assertEquals(0L, PcmShortSink.spanUs(0L))
        assertEquals(0L, PcmShortSink.spanUs(call45MinUs, call45MinUs, Long.MAX_VALUE))
    }

    @Test
    fun a_window_of_a_long_call_fits_a_256_mb_heap() {
        // 48 kHz stereo, the larger of the two layouts CallVault writes. Shorts are 2 bytes.
        val bytes = 2L * PcmShortSink.capacityFor(
            PcmShortSink.spanUs(call45MinUs, 600_000_000L, 960_000_000L), 48_000, 2,
        )
        assertTrue("a 6-minute window reserved $bytes bytes", bytes < 80L * 1024 * 1024)
    }
}
