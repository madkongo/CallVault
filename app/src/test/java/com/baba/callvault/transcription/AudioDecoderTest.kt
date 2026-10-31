/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDecoderTest {

    // ---- PCM-16 to mono float ----

    @Test
    fun `mono pcm16 is scaled into the minus one to one range`() {
        val pcm = shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE)
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 1)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f), out, 0.001f)
    }

    @Test
    fun `stereo pcm16 is averaged down to mono`() {
        // L=+full R=-full must average to silence, not to a doubled or clipped sample.
        val pcm = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 1000, 1000)
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 2)
        assertEquals(2, out.size)
        assertEquals(0f, out[0], 0.001f)
        assertEquals(1000f / 32767f, out[1], 0.001f)
    }

    @Test
    fun `an odd trailing frame in stereo input is dropped rather than read out of bounds`() {
        // Recordings made before the mandatory mono downmix are stereo, and a truncated file can end
        // mid-frame. Reading past the end here would be an out-of-bounds crash on the back-catalogue.
        val pcm = shortArrayOf(100, 200, 300) // 1.5 stereo frames
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 2)
        assertEquals(1, out.size)
    }

    @Test
    fun `empty input produces empty output rather than throwing`() {
        assertEquals(0, AudioDecoder.pcm16ToMonoFloat(shortArrayOf(), channels = 1).size)
    }

    @Test
    fun `every produced sample stays within the range whisper requires`() {
        val pcm = ShortArray(512) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        val out = AudioDecoder.pcm16ToMonoFloat(pcm, channels = 1)
        assertTrue(out.all { it in -1f..1f })
    }

    // ---- resampling ----

    @Test
    fun `audio already at the target rate is returned untouched`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out = AudioDecoder.resampleTo16k(input, inputRate = 16_000)
        assertArrayEquals(input, out, 0f)
    }

    @Test
    fun `48k is resampled to a third of the sample count`() {
        // CallVault records at 48 kHz, so 3:1 is the case that actually runs in production.
        val input = FloatArray(48_000) { 0.5f }
        val out = AudioDecoder.resampleTo16k(input, inputRate = 48_000)
        assertEquals(16_000, out.size)
    }

    @Test
    fun `a constant signal survives resampling as the same constant`() {
        // Interpolation must not introduce ripple on a flat input.
        val input = FloatArray(4_800) { 0.25f }
        val out = AudioDecoder.resampleTo16k(input, inputRate = 48_000)
        assertTrue(out.all { kotlin.math.abs(it - 0.25f) < 0.001f })
    }

    @Test
    fun `resampling empty audio yields empty audio`() {
        assertEquals(0, AudioDecoder.resampleTo16k(FloatArray(0), inputRate = 48_000).size)
    }

    // ---- anti-aliasing ----
    //
    // 48000/16000 is exactly 3, so the interpolation fraction is identically zero on the production
    // path and the resampler is pure decimation. Without a low-pass in front of it, everything from
    // 8-24 kHz folds into 0-8 kHz at full amplitude — the band whisper's mel filterbank reads.
    // Measured on 13 real recordings from a test device, the folded energy landing in 0-4 kHz sat at
    // -26 dB relative to the signal there on average and -17.5 dB at worst, so this is not theoretical.

    @Test
    fun `a 12 kHz tone does not fold back as a 4 kHz tone`() {
        // The canonical failure. Taking every third sample of a 12 kHz tone at 48 kHz produces a
        // 4 kHz tone at FULL amplitude — right in the middle of the speech band.
        val out = AudioDecoder.resampleTo16k(tone(12_000.0, 48_000, seconds = 1.0), inputRate = 48_000)
        val aliased = magnitudeAt(out, 4_000.0, 16_000)
        assertTrue(
            "12 kHz folded back to 4 kHz at amplitude $aliased; it must be attenuated by at least 40 dB",
            aliased < 0.01f,
        )
    }

    @Test
    fun `a 20 kHz tone does not fold back into the speech band`() {
        // The other fold: 16-24 kHz maps to 0-8 kHz directly rather than mirrored, so 20 kHz lands
        // at 4 kHz. A phone microphone genuinely carries content up here.
        val out = AudioDecoder.resampleTo16k(tone(20_000.0, 48_000, seconds = 1.0), inputRate = 48_000)
        assertTrue(magnitudeAt(out, 4_000.0, 16_000) < 0.01f)
    }

    @Test
    fun `speech-band tones survive the anti-alias filter intact`() {
        // The filter must not pay for its stopband with the band that carries the words. 1 kHz is
        // where most telephony energy sits and 3 kHz is near the top of the carrier passband.
        for (hz in listOf(300.0, 1_000.0, 3_000.0)) {
            val out = AudioDecoder.resampleTo16k(tone(hz, 48_000, seconds = 1.0), inputRate = 48_000)
            val kept = magnitudeAt(out, hz, 16_000)
            // A pure tone's half-amplitude shows up as 0.5 in this measure; allow 5% of loss.
            assertTrue("$hz Hz came through at $kept, expected ~0.5", kept > 0.475f)
        }
    }

    @Test
    fun `44_1 kHz still resamples, on the one path where interpolation actually runs`() {
        // 44100/16000 is not an integer, so this is the only rate where the interpolation fraction
        // is ever non-zero. It must still both filter and land on the right sample count.
        val out = AudioDecoder.resampleTo16k(tone(1_000.0, 44_100, seconds = 1.0), inputRate = 44_100)
        assertEquals(16_000, out.size)
        assertTrue(magnitudeAt(out, 1_000.0, 16_000) > 0.475f)
    }

    @Test
    fun `upsampling is left unfiltered because there is nothing above it to fold`() {
        // 8 kHz input has no content above its own 4 kHz Nyquist, so low-passing would only throw
        // away signal. The count still has to come out right.
        val out = AudioDecoder.resampleTo16k(FloatArray(8_000) { 0.25f }, inputRate = 8_000)
        assertEquals(16_000, out.size)
        assertTrue(out.all { kotlin.math.abs(it - 0.25f) < 0.001f })
    }

    @Test
    fun `the anti-alias kernel has unity gain and an integer group delay`() {
        for (rate in listOf(48_000, 44_100, 32_000)) {
            val taps = AudioDecoder.antiAliasKernel(rate)
            // Odd length keeps the delay a whole number of samples, so timestamps do not skew.
            assertEquals("kernel for $rate Hz must be odd-length", 1, taps.size % 2)
            // Unity DC gain, or the filter quietly rescales the whole recording.
            assertEquals(1f, taps.sum(), 0.001f)
            // Symmetric, which is what makes the phase response linear.
            for (k in taps.indices) assertEquals(taps[k], taps[taps.size - 1 - k], 1e-6f)
        }
    }

    // ---- single-pass decode: the same audio, without the 48 kHz float array in the middle ----
    //
    // `pcm16ToMonoFloat` followed by `resampleTo16k` held a float32 copy of the whole call *at the
    // input rate* — 172.8 MB for fifteen minutes at 48 kHz, alongside the 86.4 MB of PCM it was
    // widened from. 259 MB against a 256 MB heap is where the old 15-minute length limit
    // came from: the limit was measuring this allocation without anyone realising it.
    //
    // These tests pin the replacement to the pipeline it replaces. Equivalence is the whole point —
    // the resampler's anti-alias filter was tuned against real recordings and must not shift.

    @Test
    fun `single pass matches the two-step pipeline it replaces, for mono 48k`() {
        val pcm = ShortArray(4_800) { (kotlin.math.sin(it / 7.0) * 12_000).toInt().toShort() }

        val twoStep = AudioDecoder.resampleTo16k(AudioDecoder.pcm16ToMonoFloat(pcm, 1), 48_000)
        val singlePass = AudioDecoder.pcm16ToMono16k(pcm, 1, 48_000)

        assertArrayEquals(twoStep, singlePass, 1e-6f)
    }

    @Test
    fun `single pass matches the two-step pipeline for stereo, where the downmix also happens`() {
        // Deliberately different content per channel, so an averaging mistake cannot cancel out.
        val pcm = ShortArray(9_600) {
            if (it % 2 == 0) (kotlin.math.sin(it / 5.0) * 9_000).toInt().toShort()
            else (kotlin.math.cos(it / 11.0) * 4_000).toInt().toShort()
        }

        val twoStep = AudioDecoder.resampleTo16k(AudioDecoder.pcm16ToMonoFloat(pcm, 2), 48_000)
        val singlePass = AudioDecoder.pcm16ToMono16k(pcm, 2, 48_000)

        assertArrayEquals(twoStep, singlePass, 1e-6f)
    }

    @Test
    fun `single pass matches on 44_1 kHz, the one rate where interpolation actually runs`() {
        val pcm = ShortArray(4_410) { (kotlin.math.sin(it / 3.0) * 15_000).toInt().toShort() }

        val twoStep = AudioDecoder.resampleTo16k(AudioDecoder.pcm16ToMonoFloat(pcm, 1), 44_100)
        val singlePass = AudioDecoder.pcm16ToMono16k(pcm, 1, 44_100)

        assertArrayEquals(twoStep, singlePass, 1e-6f)
    }

    @Test
    fun `audio already at the target rate is converted but not resampled`() {
        val pcm = ShortArray(1_600) { (it % 200 - 100).toShort() }

        val twoStep = AudioDecoder.resampleTo16k(AudioDecoder.pcm16ToMonoFloat(pcm, 1), 16_000)
        val singlePass = AudioDecoder.pcm16ToMono16k(pcm, 1, 16_000)

        assertArrayEquals(twoStep, singlePass, 1e-6f)
        assertEquals("no resampling should happen at the target rate", 1_600, singlePass.size)
    }

    @Test
    fun `a 12 kHz tone still does not fold back through the single-pass path`() {
        // The aliasing guarantee is the reason the filter exists; it has to survive the rewrite.
        val loud = tone(12_000.0, 48_000, 0.5)
        val pcm = ShortArray(loud.size) { (loud[it] * 20_000).toInt().toShort() }

        val out = AudioDecoder.pcm16ToMono16k(pcm, 1, 48_000)

        assertTrue("12 kHz must not reappear at 4 kHz", magnitudeAt(out, 4_000.0, 16_000) < 0.02f)
    }

    @Test
    fun `single pass tolerates empty input and a trailing partial frame`() {
        assertEquals(0, AudioDecoder.pcm16ToMono16k(ShortArray(0), 1, 48_000).size)
        // Five shorts across two channels is two whole frames plus a stray one; the stray is dropped.
        val odd = ShortArray(5) { 1_000 }
        assertEquals(
            AudioDecoder.resampleTo16k(AudioDecoder.pcm16ToMonoFloat(odd, 2), 48_000).size,
            AudioDecoder.pcm16ToMono16k(odd, 2, 48_000).size,
        )
    }

    /** A full-scale sine, the input every aliasing assertion above is built on. */
    private fun tone(hz: Double, sampleRate: Int, seconds: Double): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { kotlin.math.sin(2.0 * Math.PI * hz * it / sampleRate).toFloat() }
    }

    /**
     * Amplitude of [hz] in [x], as a fraction of full scale. A single DFT bin rather than a whole
     * FFT: one frequency is all any of these assertions needs.
     *
     * The first and last 10% are skipped — the filter clamps at the edges, and that transient is not
     * what is under test.
     */
    private fun magnitudeAt(x: FloatArray, hz: Double, sampleRate: Int): Float {
        val from = x.size / 10
        val to = x.size - x.size / 10
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val phase = 2.0 * Math.PI * hz * i / sampleRate
            re += x[i] * kotlin.math.cos(phase)
            im += x[i] * kotlin.math.sin(phase)
        }
        return (kotlin.math.sqrt(re * re + im * im) / (to - from)).toFloat()
    }

    // ---- duration plausibility ----
    //
    // These exist because of a real incident on 2026-08-16: a 45-second clip was handed to whisper
    // malformed and transcribed for over eleven minutes before anything indicated a problem. whisper
    // does not reject an over-long buffer, it just works through it, so the arithmetic has to be
    // checked here instead.

    @Test
    fun `a decode matching the declared duration is plausible`() {
        // 45 s at 48 kHz.
        assertTrue(AudioDecoder.isPlausibleDuration(45 * 48_000, 48_000, 45_000_000L))
    }

    @Test
    fun `the eleven-minute-from-45-seconds case is rejected`() {
        // What the failed on-device run effectively did: ~15x more audio than the file declares.
        assertFalse(AudioDecoder.isPlausibleDuration(45 * 15 * 48_000, 48_000, 45_000_000L))
    }

    @Test
    fun `reading float samples as shorts would double the length and is rejected`() {
        // The concrete mis-decode this guards: 32-bit float PCM read as 16-bit doubles the count.
        assertFalse(AudioDecoder.isPlausibleDuration(45 * 48_000 * 2 + 1, 48_000, 45_000_000L))
    }

    @Test
    fun `a wrong sample rate assumption that triples the audio is rejected`() {
        // Believing the file's 48 kHz when the decoder actually emitted 16 kHz.
        assertFalse(AudioDecoder.isPlausibleDuration(45 * 48_000 * 3, 48_000, 45_000_000L))
    }

    @Test
    fun `an unknown declared duration is not treated as a failure`() {
        // Some containers do not declare one; that is not evidence of a bug.
        assertTrue(AudioDecoder.isPlausibleDuration(1_000, 48_000, 0L))
    }

    @Test
    fun `small imprecision is tolerated rather than policed`() {
        // A few dropped frames must not fail a decode; the guard is for order-of-magnitude errors.
        assertTrue(AudioDecoder.isPlausibleDuration((45 * 48_000 * 0.97).toInt(), 48_000, 45_000_000L))
    }

    @Test
    fun `a nonsensical sample rate is implausible`() {
        assertFalse(AudioDecoder.isPlausibleDuration(1_000, 0, 45_000_000L))
    }
}
