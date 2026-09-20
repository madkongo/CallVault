/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import org.junit.Assert.assertEquals
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

/**
 * Measuring whether a real file's two channels differ, over the same interleaved PCM-16 the
 * [SpeakerTurnDetector] eats — so one decode pass answers both questions.
 *
 * The cases below are built from synthetic audio rather than asserted numbers, because the thing worth
 * pinning is the *verdict* on a shape of signal, not an intermediate decibel value.
 */
class StereoSeparationMeterTest {

    /** Interleaved stereo PCM-16 from two per-channel sample generators. */
    private fun pcm(frames: Int, left: (Int) -> Double, right: (Int) -> Double): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = (left(i) * Short.MAX_VALUE).toInt().toShort()
            val r = (right(i) * Short.MAX_VALUE).toInt().toShort()
            out[i * 4] = (l.toInt() and 0xFF).toByte()
            out[i * 4 + 1] = (l.toInt() shr 8).toByte()
            out[i * 4 + 2] = (r.toInt() and 0xFF).toByte()
            out[i * 4 + 3] = (r.toInt() shr 8).toByte()
        }
        return out
    }

    private fun verdict(data: ByteArray): StereoSeparation =
        StereoSeparationMeter().apply { accept(data, data.size) }.separation()

    @Test
    fun `the same signal on both channels reads as mixed`() {
        // What a collapsing low-bit-rate encoder leaves behind, and what a mono source looks like.
        val tone = pcm(8000, { sin(2 * PI * 440 * it / 48000.0) * 0.5 }, { sin(2 * PI * 440 * it / 48000.0) * 0.5 })
        assertEquals(StereoSeparation.MIXED, verdict(tone))
    }

    @Test
    fun `two different tones read as separated`() {
        // Two parties talking: uncorrelated content, so the side channel carries real energy.
        val two = pcm(8000, { sin(2 * PI * 440 * it / 48000.0) * 0.5 }, { sin(2 * PI * 1310 * it / 48000.0) * 0.5 })
        assertEquals(StereoSeparation.SEPARATED, verdict(two))
    }

    @Test
    fun `one side talking and the other silent reads as separated`() {
        // The commonest real shape: strictly alternating turns. Half the file looks like this.
        val oneSided = pcm(8000, { sin(2 * PI * 440 * it / 48000.0) * 0.5 }, { 0.0 })
        assertEquals(StereoSeparation.SEPARATED, verdict(oneSided))
    }

    @Test
    fun `digital silence is unknown, not mixed`() {
        assertEquals(StereoSeparation.UNKNOWN, verdict(pcm(8000, { 0.0 }, { 0.0 })))
    }

    @Test
    fun `a file that was never fed is unknown`() {
        assertEquals(StereoSeparation.UNKNOWN, StereoSeparationMeter().separation())
    }

    @Test
    fun `only the bytes the caller vouches for are read`() {
        // Same contract as SpeakerTurnDetector.accept: a decoder hands over a big buffer and says how
        // much of it is real. Reading past `len` would score stale audio from the previous chunk.
        val quiet = pcm(4000, { 0.0 }, { 0.0 })
        val loud = pcm(4000, { 0.5 }, { -0.5 })
        val buffer = quiet + loud
        // Vouch only for the silent half.
        val meter = StereoSeparationMeter().apply { accept(buffer, quiet.size) }
        assertEquals(StereoSeparation.UNKNOWN, meter.separation())
    }

    @Test
    fun `a trailing partial frame is left alone rather than misread`() {
        val full = pcm(100, { 0.5 }, { -0.5 })
        val ragged = full + byteArrayOf(1, 2, 3)
        val meter = StereoSeparationMeter().apply { accept(ragged, ragged.size) }
        assertEquals(StereoSeparation.SEPARATED, meter.separation())
    }

    // ---- The ShortArray path the decoder actually uses ----

    /** Interleaved PCM-16 as shorts, the shape AudioDecoder hands out. */
    private fun shorts(frames: Int, left: (Int) -> Double, right: (Int) -> Double): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            out[i * 2] = (left(i) * Short.MAX_VALUE).toInt().toShort()
            out[i * 2 + 1] = (right(i) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    @Test
    fun `the short path agrees with the byte path`() {
        // Both are fed by real code -- bytes from the live capture, shorts from the decoder -- so a
        // disagreement between them would make a file's verdict depend on which door it came through.
        val l = { i: Int -> sin(2 * PI * 440 * i / 48000.0) * 0.5 }
        val r = { i: Int -> sin(2 * PI * 1310 * i / 48000.0) * 0.5 }

        val viaBytes = verdict(pcm(8000, l, r))
        val viaShorts = StereoSeparationMeter().apply { accept(shorts(8000, l, r), 16000, 2) }.separation()

        assertEquals(viaBytes, viaShorts)
        assertEquals(StereoSeparation.SEPARATED, viaShorts)
    }

    @Test
    fun `a mono file is ignored rather than judged`() {
        // One channel cannot hold two parties, and folding anything else into a left/right question
        // would invent an answer. UNKNOWN is the honest output, and it stops labelling.
        val mono = ShortArray(4000) { (sin(2 * PI * 440 * it / 48000.0) * 0.5 * Short.MAX_VALUE).toInt().toShort() }
        val meter = StereoSeparationMeter().apply { accept(mono, mono.size, channels = 1) }
        assertEquals(StereoSeparation.UNKNOWN, meter.separation())
    }

    @Test
    fun `more than two channels is ignored too`() {
        val surround = ShortArray(6000) { 1000 }
        val meter = StereoSeparationMeter().apply { accept(surround, surround.size, channels = 6) }
        assertEquals(StereoSeparation.UNKNOWN, meter.separation())
    }
}
