/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import java.nio.ByteBuffer

/**
 * Accumulates decoded PCM-16 as **shorts**, in one buffer, with no staging and no trimming copy.
 *
 * The decoder used to write MediaCodec's output into a `ByteArrayOutputStream`, call `toByteArray()`,
 * and then copy that into a `ShortArray`. For a fifteen-minute call at 48 kHz that is 86.4 MB of
 * audio living in three full-length buffers at overlapping moments — the stream's internal array
 * (grown by doubling, so up to 172.8 MB), the byte copy, and the short array. The peak lands around
 * 220 MB against a 256 MB heap, entirely in service of changing the element type.
 *
 * So: decode straight into shorts, size the buffer from the container's declared duration so it
 * normally never grows, and expose [array] with [size] rather than returning a trimmed copy. The
 * consumer reads [size] elements and ignores the tail.
 *
 * **[array] is the live buffer, not a snapshot.** It is valid only up to [size], and a later [append]
 * may replace it. That is the whole point — a defensive copy here would reintroduce the second
 * full-length allocation this class exists to remove.
 *
 * Not thread-safe: one sink per decode, written by the one thread draining the codec.
 */
internal class PcmShortSink(initialCapacity: Int) {

    /**
     * The buffer. Read [size] elements; anything beyond that is uninitialised or stale.
     *
     * `coerceAtLeast(1)` because a zero-length array can never grow by a ratio.
     */
    var array: ShortArray = ShortArray(initialCapacity.coerceAtLeast(1))
        private set

    /** How many samples have been appended. */
    var size: Int = 0
        private set

    /**
     * Appends the first [byteCount] bytes of [buffer] as little-endian PCM-16.
     *
     * [byteCount] is what the codec reported, which is normally less than the buffer's capacity;
     * reading further would append stale audio from a previous callback. A trailing odd byte is
     * dropped rather than combined with whatever follows it.
     */
    fun append(buffer: ByteBuffer, byteCount: Int) {
        val samples = byteCount / 2
        if (samples <= 0) return
        ensureCapacity(size + samples)
        // A ShortBuffer view over the same memory: the byte-order conversion happens in the copy,
        // with no intermediate array of our own.
        buffer.asShortBuffer().get(array, size, samples)
        size += samples
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= array.size) return
        // 1.5x rather than 2x. At these sizes the difference is tens of megabytes of slack on a heap
        // that is the constraint in the first place, and a correctly pre-sized sink never gets here.
        val grown = (array.size + (array.size shr 1)).coerceAtLeast(needed)
        array = array.copyOf(grown)
    }

    companion object {
        /**
         * Samples to reserve for a stream of [durationUs] at [sampleRate] with [channels] channels,
         * or a small default when the container declares no duration.
         *
         * Slightly over-reserved: a decoder that emits a few frames more than the declared duration
         * is ordinary, and one reallocation costs more than the slack does. Capped so a container
         * lying about its duration cannot ask for an unbounded allocation — the wrong number here
         * should cost a copy, never an OutOfMemoryError.
         */
        fun capacityFor(durationUs: Long, sampleRate: Int, channels: Int): Int {
            if (durationUs <= 0 || sampleRate <= 0 || channels <= 0) return DEFAULT_CAPACITY
            val samples = durationUs / 1_000_000.0 * sampleRate * channels * HEADROOM
            return samples.toLong().coerceIn(DEFAULT_CAPACITY.toLong(), MAX_CAPACITY.toLong()).toInt()
        }

        /**
         * How much audio a decode of [fromUs]..[toUs] is expected to produce, for [capacityFor].
         *
         * A range, not the whole file. Sizing a five-minute window from the call's declared length
         * reserved the WHOLE call for every window — 264 MB per window of a 45-minute call — so the
         * windowed waveform and transcription paths could run a 256 MB heap out on exactly the long
         * calls they exist for (issue #63). [SEEK_RUN_UP_US] covers a seek that lands early, on the
         * sync point before [fromUs]. 0 when nothing is known, which [capacityFor] treats as "small".
         */
        fun spanUs(declaredUs: Long, fromUs: Long = 0L, toUs: Long = Long.MAX_VALUE): Long {
            val start = fromUs.coerceAtLeast(0L)
            val end = when {
                toUs == Long.MAX_VALUE -> declaredUs
                declaredUs > 0L -> minOf(toUs, declaredUs)
                else -> toUs
            }
            if (end <= start) return 0L
            return end - start + if (start > 0L) SEEK_RUN_UP_US else 0L
        }

        /** Room for a seek that lands on the sync point before the one asked for. */
        private const val SEEK_RUN_UP_US = 2_000_000L

        private const val HEADROOM = 1.02

        /** 64 Ki samples — a couple of seconds of mono, and nothing on a heap this size. */
        private const val DEFAULT_CAPACITY = 1 shl 16

        /** Two hours of 48 kHz stereo. Past this the duration is not to be believed. */
        private const val MAX_CAPACITY = 2 * 3_600 * 48_000 * 2
    }
}
