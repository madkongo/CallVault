/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.echo

import kotlin.math.abs
import kotlin.math.max

/**
 * Lines the far side up with the copy of it that leaks back into the near mic, so the mono mix holds
 * one far voice instead of two.
 *
 * Why: on some phones (OnePlus, Android 17, earpiece; see the 2026-10-31 echo dev-note) the user's mic
 * picks up the other person from the earpiece 60–76 ms late, as loud as the clean far capture. Summing
 * the two gives the same voice twice that far apart, which is heard as echo. Delaying the clean copy by
 * the same amount makes the two land together. The maintainer chose this over turning the far side
 * down or dropping it (2026-11-01): it never makes the other person quieter.
 *
 * Takes 20 ms chunks of 48 kHz mono PCM-16 from both sides. Until a delay is settled the far side passes
 * through untouched; with no leak (a headset, a quiet earpiece) it stays that way for the whole call.
 * A new delay takes effect while the far side is quiet, so the jump is not heard.
 */
class EchoAligner(
    /**
     * Runs one delay search. Default: a short-lived background thread, so a slow phone never holds up
     * the capture loop (the search is ~10 ms on a laptop, unknown inside app_process). Tests run it inline.
     */
    private val runSearch: (Runnable) -> Unit = { Thread(it, "CV-EchoAlign").apply { isDaemon = true }.start() },
) {

    /** The far delay now applied, in 48 kHz frames. 0 = untouched. */
    var appliedDelayFrames: Int = 0
        private set

    val appliedDelayMs: Double get() = appliedDelayFrames * 1000.0 / SAMPLE_RATE

    /** Last estimate, for the capture diagnostics. */
    var lastEstimate: String = "none"
        private set

    private val nearHist = DoubleArray(HIST)
    private val farHist = DoubleArray(HIST)
    private var histPos = 0
    private var histFilled = 0
    private val estimator = EchoLagEstimator(HIST, MIN_LAG_16K, MAX_LAG_16K)
    private val recent = ArrayDeque<EchoLagEstimator.Estimate>()
    private var chunksSinceAnalysis = 0
    private val nearCopy = DoubleArray(HIST)
    private val farCopy = DoubleArray(HIST)
    private val searching = java.util.concurrent.atomic.AtomicBoolean(false)
    /** A finished search waiting to be taken by the capture thread. */
    private val finished = java.util.concurrent.atomic.AtomicReference<EchoLagEstimator.Estimate?>(null)

    private val farRing = ShortArray(RING)
    private var ringPos = 0
    private var pendingFrames = -1
    private var pendingChunks = 0

    /** Writes the far chunk to [out], delayed by the settled leak delay. All three are CHUNK_BYTES long. */
    fun alignFar(near: ByteArray, far: ByteArray, out: ByteArray) {
        var farPeak = 0
        for (k in 0 until CHUNK_FRAMES) {
            val s = sample(far, k)
            farRing[(ringPos + k) % RING] = s.toShort()
            farPeak = max(farPeak, abs(s))
        }
        record(near, far)
        maybeAnalyse()
        maybeApply(farPeak)
        for (k in 0 until CHUNK_FRAMES) {
            val idx = Math.floorMod(ringPos + k - appliedDelayFrames, RING)
            val v = farRing[idx].toInt()
            out[2 * k] = (v and 0xFF).toByte(); out[2 * k + 1] = (v shr 8).toByte()
        }
        ringPos = (ringPos + CHUNK_FRAMES) % RING
    }

    /** Keeps 16 kHz histories of both sides (each 3 samples averaged) for the delay search. */
    private fun record(near: ByteArray, far: ByteArray) {
        for (k in 0 until CHUNK_FRAMES step DECIMATE) {
            var n = 0.0; var f = 0.0
            for (j in 0 until DECIMATE) { n += sample(near, k + j); f += sample(far, k + j) }
            nearHist[histPos] = n / (DECIMATE * 32768.0)
            farHist[histPos] = f / (DECIMATE * 32768.0)
            histPos = (histPos + 1) % HIST
        }
        histFilled = minOf(HIST, histFilled + CHUNK_FRAMES / DECIMATE)
    }

    private fun maybeAnalyse() {
        finished.getAndSet(null)?.let { take(it) }
        if (++chunksSinceAnalysis < ANALYSE_EVERY_CHUNKS || histFilled < HIST) return
        // Still busy with the last one: skip this second rather than queue work up.
        if (!searching.compareAndSet(false, true)) return
        chunksSinceAnalysis = 0
        // Copies, so the capture thread can keep writing the histories while the search runs. Reused:
        // nothing else touches them while a search is in flight.
        val near = nearCopy
        val far = farCopy
        for (i in 0 until HIST) {
            near[i] = nearHist[(histPos + i) % HIST]
            far[i] = farHist[(histPos + i) % HIST]
        }
        runSearch(Runnable {
            try {
                estimator.estimate(near, far, MIN_FAR_RMS)?.let { finished.set(it) }
            } finally {
                searching.set(false)
            }
        })
        finished.getAndSet(null)?.let { take(it) }
    }

    private fun take(e: EchoLagEstimator.Estimate) {
        lastEstimate = String.format(java.util.Locale.ROOT, "%.1fms peak %.2f x%.1f", e.lag * 1000.0 / RATE_16K, e.peak, e.prominence)
        recent.addLast(e)
        if (recent.size > AGREE) recent.removeFirst()
        settledDelay()?.let { frames ->
            if (abs(frames - appliedDelayFrames) > RETUNE_TOLERANCE_FRAMES) {
                if (pendingFrames < 0) pendingChunks = 0
                pendingFrames = frames
            }
        }
    }

    /** The delay in 48 kHz frames when the last few estimates agree on a strong, audible leak. */
    private fun settledDelay(): Int? {
        if (recent.size < AGREE) return null
        if (recent.any { it.prominence < MIN_PROMINENCE }) return null
        val lags = recent.map { it.lag }.sorted()
        if (lags.last() - lags.first() > AGREE_SPREAD_16K) return null
        return lags[lags.size / 2] * DECIMATE
    }

    private fun maybeApply(farPeak: Int) {
        if (pendingFrames < 0) return
        pendingChunks++
        if (farPeak < QUIET_PEAK || pendingChunks >= FORCE_AFTER_CHUNKS) {
            appliedDelayFrames = pendingFrames
            pendingFrames = -1
        }
    }

    private fun sample(b: ByteArray, k: Int): Int =
        ((b[2 * k].toInt() and 0xFF) or (b[2 * k + 1].toInt() shl 8)).toShort().toInt()

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHUNK_FRAMES = 960                    // 20 ms, as VoipCaptureSession hands them over
        private const val DECIMATE = 3
        private const val RATE_16K = SAMPLE_RATE / DECIMATE
        /** About 2 s of history at 16 kHz; a power of two for the FFT. */
        private const val HIST = 32_768
        /** Search up to 250 ms; the reports measured 59 and 75 ms. */
        private const val MAX_LAG_16K = RATE_16K / 4
        private const val MAX_DELAY_FRAMES = MAX_LAG_16K * DECIMATE
        private const val RING = 16_384                 // > MAX_DELAY_FRAMES + CHUNK_FRAMES
        /** Two copies closer than this fuse into one sound; there is no echo to fix. */
        private const val MIN_LAG_16K = RATE_16K / 50   // 20 ms
        private const val ANALYSE_EVERY_CHUNKS = 50     // once a second
        /** Far side quieter than about -40 dBFS over the window: nothing to look for. */
        private const val MIN_FAR_RMS = 0.01
        /**
         * How far the peak must stand above the rest of the search (peak / RMS). On the two report calls
         * the leak stood at x11-x30; an unrelated mic gave x3.6-x5.4, with one stray x14 that the
         * agreement rule below threw out.
         */
        private const val MIN_PROMINENCE = 8.0
        /** Consecutive estimates that must agree before the far side is moved. */
        private const val AGREE = 3
        private const val AGREE_SPREAD_16K = RATE_16K / 1000   // 1 ms
        private const val RETUNE_TOLERANCE_FRAMES = SAMPLE_RATE / 1000
        /** A far chunk peaking under this (about -36 dBFS) is quiet enough to move the delay unheard. */
        private const val QUIET_PEAK = 500
        /** Move anyway after 10 s without a quiet moment. */
        private const val FORCE_AFTER_CHUNKS = 500

        init { check(RING > MAX_DELAY_FRAMES + CHUNK_FRAMES) }
    }
}
