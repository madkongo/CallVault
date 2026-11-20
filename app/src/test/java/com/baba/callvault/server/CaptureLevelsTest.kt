package com.baba.callvault.server

import com.baba.callvault.server.CaptureSilence.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CaptureLevelsTest {

    private val rate = 16_000

    /** Interleaved PCM-16 of [seconds]: each channel a sine of the given amplitude (0 = digital silence). */
    private fun pcm(seconds: Int, vararg amplitudes: Int): ByteArray {
        val frames = seconds * rate
        val out = ByteArray(frames * amplitudes.size * 2)
        var o = 0
        for (f in 0 until frames) {
            for (amp in amplitudes) {
                val s = if (amp == 0) 0 else (amp * sin(2 * PI * 220 * f / rate)).toInt()
                out[o++] = (s and 0xFF).toByte()
                out[o++] = (s shr 8).toByte()
            }
        }
        return out
    }

    private fun measure(channels: Int, bytes: ByteArray): CaptureLevels {
        val meter = CaptureLevelMeter(channels, rate)
        var at = 0
        while (at < bytes.size) { // fed in chunks, the way the capture loops do
            val n = minOf(4096, bytes.size - at)
            meter.accept(bytes.copyOfRange(at, at + n), n)
            at += n
        }
        return meter.levels()
    }

    @Test
    fun `a full-length file of digital zeros is nothing heard`() {
        val levels = measure(2, pcm(10, 0, 0))
        assertEquals(0, levels.loudest)
        assertEquals(Verdict.NOTHING_HEARD, CaptureSilence.judge(levels))
    }

    @Test
    fun `a mono capture of zeros is nothing heard`() {
        assertEquals(Verdict.NOTHING_HEARD, CaptureSilence.judge(measure(1, pcm(10, 0))))
    }

    @Test
    fun `dither at or under the threshold on both channels still counts as silence`() {
        assertEquals(Verdict.NOTHING_HEARD, CaptureSilence.judge(measure(2, pcm(10, 60, 100))))
    }

    @Test
    fun `a near-silent but real far side one step over the threshold is sound`() {
        // 101 of 32768 is about -50 dBFS: far quieter than a whisper over a poor line, and still sound.
        assertEquals(Verdict.SOUND, CaptureSilence.judge(measure(2, pcm(10, 0, 101))))
    }

    @Test
    fun `a quiet whisper on one channel and zeros on the other is sound`() {
        val levels = measure(2, pcm(10, 300, 0))
        assertEquals(300, levels.peak0)
        assertEquals(0, levels.peak1)
        assertEquals(Verdict.SOUND, CaptureSilence.judge(levels))
    }

    @Test
    fun `one short word in a long silent call is sound because the peak decides, not the average`() {
        val bytes = pcm(30, 0, 0)
        val word = pcm(1, 0, 2_000)
        System.arraycopy(word, 0, bytes, 12 * rate * 4, word.size)
        assertEquals(Verdict.SOUND, CaptureSilence.judge(measure(2, bytes)))
    }

    @Test
    fun `negative full-scale samples are measured as magnitudes`() {
        val bytes = byteArrayOf(0x00, 0x80.toByte(), 0, 0) // -32768 on ch0, 0 on ch1
        val levels = measure(2, bytes)
        assertEquals(32_768, levels.peak0)
    }

    @Test
    fun `a capture under five seconds is not judged`() {
        assertEquals(Verdict.UNKNOWN, CaptureSilence.judge(measure(2, pcm(4, 0, 0))))
    }

    @Test
    fun `no measurement is unknown, never silence`() {
        assertEquals(Verdict.UNKNOWN, CaptureSilence.judge(null))
    }

    @Test
    fun `a trailing partial frame is ignored`() {
        val meter = CaptureLevelMeter(2, rate)
        meter.accept(byteArrayOf(1, 0, 2, 0, 9), 5)
        assertEquals(1, meter.levels().peak0)
    }

    @Test
    fun `the levels line survives a round trip inside the recorder diagnostics`() {
        val levels = measure(2, pcm(6, 400, 0))
        val diagnostics = "overrunMs=5\n" + levels.line() + "\ncapture recovery: shellApp=ok"
        assertEquals(levels, CaptureLevels.parse(diagnostics))
        assertEquals("overrunMs=5\ncapture recovery: shellApp=ok", CaptureLevels.strip(diagnostics))
    }

    @Test
    fun `a missing or garbled levels line parses to null`() {
        assertNull(CaptureLevels.parse(null))
        assertNull(CaptureLevels.parse("overrunMs=5"))
        assertNull(CaptureLevels.parse("capture levels: ch=x ms=1"))
    }
}
