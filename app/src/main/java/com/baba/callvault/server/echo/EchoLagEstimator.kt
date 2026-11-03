/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.echo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds how late the far voice reappears in the near mic, by GCC-PHAT over the last couple of seconds.
 *
 * Works at 16 kHz on copies of the two histories the aligner keeps. Not thread-safe: one search at a time. The phase transform whitens both sides, so
 * the result is a sharp peak at the delay of any copy of the far voice in the mic, whatever its level,
 * and the user's own voice (which is not in the far side) only adds a low, flat floor.
 */
internal class EchoLagEstimator(private val size: Int, private val minLag: Int, private val maxLag: Int) {

    /** [peak] is the whitened correlation at [lag]; [prominence] is that peak over the RMS of the whole search range. */
    data class Estimate(val lag: Int, val peak: Double, val prominence: Double)

    private val nRe = DoubleArray(2 * size)
    private val nIm = DoubleArray(2 * size)
    private val fRe = DoubleArray(2 * size)
    private val fIm = DoubleArray(2 * size)

    /** [near] and [far] are the same [size] samples of time, oldest first. Null when the far side is too quiet. */
    fun estimate(near: DoubleArray, far: DoubleArray, minFarRms: Double): Estimate? {
        if (rms(far) < minFarRms) return null
        nRe.fill(0.0); nIm.fill(0.0); fRe.fill(0.0); fIm.fill(0.0)
        near.copyInto(nRe); far.copyInto(fRe)
        Fft.transform(nRe, nIm, inverse = false)
        Fft.transform(fRe, fIm, inverse = false)
        for (k in nRe.indices) {
            // near * conj(far), then keep only the phase.
            val re = nRe[k] * fRe[k] + nIm[k] * fIm[k]
            val im = nIm[k] * fRe[k] - nRe[k] * fIm[k]
            val mag = sqrt(re * re + im * im) + 1e-12
            nRe[k] = re / mag; nIm[k] = im / mag
        }
        Fft.transform(nRe, nIm, inverse = true)
        // Only delays long enough to be heard as echo. A copy at ~0 ms (seen in a real call, peak 0.47)
        // would otherwise win and hide the leak.
        var best = minLag
        var sumSq = 0.0
        for (lag in minLag..maxLag) {
            sumSq += nRe[lag] * nRe[lag]
            if (abs(nRe[lag]) > abs(nRe[best])) best = lag
        }
        val floor = sqrt(sumSq / (maxLag - minLag + 1)) + 1e-12
        return Estimate(best, abs(nRe[best]), abs(nRe[best]) / floor)
    }

    private fun rms(x: DoubleArray): Double {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size)
    }
}
