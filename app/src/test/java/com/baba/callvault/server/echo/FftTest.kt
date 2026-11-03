package com.baba.callvault.server.echo

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class FftTest {

    @Test
    fun `an impulse transforms to a flat spectrum`() {
        val re = DoubleArray(16).also { it[0] = 1.0 }
        val im = DoubleArray(16)
        Fft.transform(re, im, inverse = false)
        for (k in 0 until 16) {
            assertEquals(1.0, re[k], 1e-12)
            assertEquals(0.0, im[k], 1e-12)
        }
    }

    @Test
    fun `inverse undoes forward`() {
        val rnd = Random(7)
        val x = DoubleArray(1024) { rnd.nextDouble(-1.0, 1.0) }
        val re = x.copyOf()
        val im = DoubleArray(1024)
        Fft.transform(re, im, inverse = false)
        Fft.transform(re, im, inverse = true)
        for (i in x.indices) assertEquals(x[i], re[i], 1e-9)
    }
}
