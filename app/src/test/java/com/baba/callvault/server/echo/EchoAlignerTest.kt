package com.baba.callvault.server.echo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic calls at 48 kHz in 20 ms chunks, shaped like the OnePlus report: the far voice reaches the
 * near mic again some tens of ms later, on top of the user's own voice.
 */
class EchoAlignerTest {

    private val chunk = EchoAligner.CHUNK_FRAMES

    /** Speech-like far side: 1.5 s of noise, 0.5 s of silence, repeating. */
    private fun farSignal(seconds: Int, rnd: Random, alwaysTalking: Boolean = false) =
        ShortArray(seconds * 48_000) { i ->
            val talking = alwaysTalking || (i % 96_000) < 72_000
            if (talking) (rnd.nextDouble(-1.0, 1.0) * 6000).toInt().toShort() else 0
        }

    private fun nearWithLeak(far: ShortArray, leakMs: Double?, rnd: Random) = ShortArray(far.size) { i ->
        val own = rnd.nextDouble(-1.0, 1.0) * 2000
        val leak = if (leakMs == null) 0.0 else {
            val j = i - (leakMs * 48).toInt()
            if (j >= 0) far[j] * 1.2 else 0.0
        }
        (own + leak).coerceIn(-32768.0, 32767.0).toInt().toShort()
    }

    private fun bytes(s: ShortArray, from: Int): ByteArray = ByteArray(chunk * 2).also { b ->
        for (k in 0 until chunk) {
            val v = s[from + k].toInt()
            b[2 * k] = (v and 0xFF).toByte(); b[2 * k + 1] = (v shr 8).toByte()
        }
    }

    /** Runs the whole call through the aligner and returns the far side it wrote. */
    private fun run(aligner: EchoAligner, near: ShortArray, far: ShortArray): ShortArray {
        val out = ShortArray(far.size)
        val o = ByteArray(chunk * 2)
        for (from in 0 until far.size - chunk + 1 step chunk) {
            aligner.alignFar(bytes(near, from), bytes(far, from), o)
            for (k in 0 until chunk) {
                out[from + k] = ((o[2 * k].toInt() and 0xFF) or (o[2 * k + 1].toInt() shl 8)).toShort()
            }
        }
        return out
    }

    @Test
    fun `a far voice leaking 75 ms late is lined up`() {
        val rnd = Random(1)
        val far = farSignal(20, rnd)
        val near = nearWithLeak(far, 75.0, rnd)
        val aligner = EchoAligner { it.run() }
        val out = run(aligner, near, far)

        assertEquals(75.0, aligner.appliedDelayMs, 0.5)
        // The last 4 s: the written far side is the input delayed by exactly the leak.
        val d = aligner.appliedDelayFrames
        val tail = 16 * 48_000
        assertArrayEquals(far.copyOfRange(tail - d, far.size - d), out.copyOfRange(tail, far.size))
    }

    @Test
    fun `the 59 ms delay of the second test call is found too`() {
        val rnd = Random(2)
        val far = farSignal(15, rnd)
        val aligner = EchoAligner { it.run() }
        run(aligner, nearWithLeak(far, 58.7, rnd), far)
        assertEquals(58.7, aligner.appliedDelayMs, 0.5)
    }

    @Test
    fun `no leak, as with a headset, leaves the far side untouched`() {
        val rnd = Random(3)
        val far = farSignal(20, rnd)
        val aligner = EchoAligner { it.run() }
        val out = run(aligner, nearWithLeak(far, null, rnd), far)
        assertEquals(0.0, aligner.appliedDelayMs, 0.0)
        assertArrayEquals(far, out)
    }

    @Test
    fun `a leak too short to sound like echo is left alone`() {
        val rnd = Random(4)
        val far = farSignal(15, rnd)
        val aligner = EchoAligner { it.run() }
        run(aligner, nearWithLeak(far, 8.0, rnd), far)
        assertEquals(0.0, aligner.appliedDelayMs, 0.0)
    }

    @Test
    fun `a silent far side never moves anything`() {
        val rnd = Random(5)
        val far = ShortArray(15 * 48_000)
        val aligner = EchoAligner { it.run() }
        run(aligner, nearWithLeak(far, 75.0, rnd), far)
        assertEquals(0.0, aligner.appliedDelayMs, 0.0)
    }

    @Test
    fun `a far side that never pauses is still lined up, after a wait`() {
        val rnd = Random(6)
        val far = farSignal(25, rnd, alwaysTalking = true)
        val aligner = EchoAligner { it.run() }
        run(aligner, nearWithLeak(far, 75.0, rnd), far)
        assertEquals(75.0, aligner.appliedDelayMs, 0.5)
    }

    @Test
    fun `the search also works on its background thread`() {
        val rnd = Random(8)
        val far = farSignal(20, rnd)
        val near = nearWithLeak(far, 75.0, rnd)
        val aligner = EchoAligner()
        val o = ByteArray(chunk * 2)
        for ((n, from) in (0 until far.size - chunk + 1 step chunk).withIndex()) {
            aligner.alignFar(bytes(near, from), bytes(far, from), o)
            if (n % 10 == 0) Thread.sleep(2)    // roughly real time, so searches finish between seconds
        }
        assertEquals(75.0, aligner.appliedDelayMs, 0.5)
    }
}
