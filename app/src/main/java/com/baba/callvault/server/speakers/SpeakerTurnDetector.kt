/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import kotlin.math.abs
import kotlin.math.min

/**
 * Works out who was speaking, from the interleaved stereo buffer the capture loop already holds.
 *
 * The two call directions arrive on separate channels — one the near party, one the far — and are
 * averaged to mono before encoding. Comparing the two channels' energy *before* that average gives
 * exact speaker turns for free: no model, no extra download, and not a byte of change to the audio
 * that gets written.
 *
 * **Runs on the capture thread of a live recording**, so it allocates nothing per chunk and does no
 * work beyond a running sum. Anything heavier belongs somewhere else.
 *
 * Not thread-safe: one instance per recording, fed from the one thread that reads the AudioRecord.
 */
class SpeakerTurnDetector(sampleRate: Int) {

    private val framesPerWindow: Int = (sampleRate * WINDOW_MS / MILLIS_PER_SECOND).toInt()

    private val turns = mutableListOf<SpeakerTurn>()

    /** Running totals for the window being filled. Longs: a window is thousands of samples. */
    private var framesInWindow = 0
    private var sumLeft = 0L
    private var sumRight = 0L

    /** Windows completed so far — the clock, since every window is exactly [WINDOW_MS]. */
    private var windowsSeen = 0L

    /** Whatever [finish] would report last, used to coalesce runs of the same speaker. */
    private var lastChannel: SpeakerChannel? = null

    /**
     * Accumulates [len] bytes of interleaved stereo PCM-16 from [pcm].
     *
     * [len] is the count the AudioRecord actually returned, which is normally less than the buffer;
     * reading further would score stale audio from the previous read. A trailing partial frame is
     * left unconsumed, matching `PcmDownmix.stereoToMono`.
     */
    fun accept(pcm: ByteArray, len: Int) {
        val limit = min(len, pcm.size)
        var index = 0

        while (index + BYTES_PER_FRAME <= limit) {
            sumLeft += abs(sampleAt(pcm, index))
            sumRight += abs(sampleAt(pcm, index + BYTES_PER_SAMPLE))
            index += BYTES_PER_FRAME

            if (++framesInWindow == framesPerWindow) closeWindow()
        }
    }

    /**
     * Accumulates [length] samples of interleaved stereo PCM-16 held as shorts.
     *
     * The live capture reads bytes from an AudioRecord; a file read back through `AudioDecoder` is
     * already shorts. Converting one to the other would allocate a copy of every chunk, which on a
     * whole-call single pass is tens of megabytes at the moment memory is tightest.
     *
     * [StereoSeparationMeterTest] pins that the two doors agree on the same audio.
     */
    fun accept(pcm: ShortArray, length: Int) {
        val limit = min(length, pcm.size)
        var index = 0

        while (index + SAMPLES_PER_FRAME <= limit) {
            sumLeft += abs(pcm[index].toInt())
            sumRight += abs(pcm[index + 1].toInt())
            index += SAMPLES_PER_FRAME

            if (++framesInWindow == framesPerWindow) closeWindow()
        }
    }

    /**
     * Ends the recording and returns the turns, oldest first.
     *
     * Empty when no audio was ever offered — which is exactly what a mono capture produces, and what
     * callers must treat as "no speaker data" rather than as an error.
     */
    fun finish(): List<SpeakerTurn> {
        if (framesInWindow > 0) closeWindow()
        return turns.toList()
    }

    /** Classifies the filled window, appends a turn if the speaker changed, and resets the sums. */
    private fun closeWindow() {
        val frames = framesInWindow.toLong()
        val channel = classify(sumLeft / frames, sumRight / frames)

        if (channel != lastChannel) {
            turns += SpeakerTurn(startMs = windowsSeen * WINDOW_MS, channel = channel)
            lastChannel = channel
        }

        windowsSeen++
        framesInWindow = 0
        sumLeft = 0L
        sumRight = 0L
    }

    /**
     * Decides a window from the two channels' mean amplitude.
     *
     * A channel has to be clearly louder to win, not merely louder: the two directions leak into each
     * other on a real call, and a bare `>` comparison would flap between speakers through every pause.
     */
    private fun classify(meanLeft: Long, meanRight: Long): SpeakerChannel {
        if (meanLeft < SILENCE_FLOOR && meanRight < SILENCE_FLOOR) return SpeakerChannel.SILENCE

        val louder = maxOf(meanLeft, meanRight)
        val quieter = minOf(meanLeft, meanRight)

        // Guard the zero case explicitly: one channel at true silence is dominance, not a divide.
        if (quieter <= 0L) return if (meanLeft > meanRight) SpeakerChannel.A else SpeakerChannel.B

        if (louder < quieter * DOMINANCE_RATIO) return SpeakerChannel.BOTH

        return if (meanLeft > meanRight) SpeakerChannel.A else SpeakerChannel.B
    }

    /** Reads a little-endian PCM-16 sample at [offset]. */
    private fun sampleAt(pcm: ByteArray, offset: Int): Int =
        ((pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()

    private companion object {
        /** How finely a turn boundary is placed. Finer costs storage and buys nothing legible. */
        const val WINDOW_MS = 100L

        /** Samples per stereo frame — one left, one right. */
        const val SAMPLES_PER_FRAME = 2

        /** Mean |sample| below which a channel counts as quiet (PCM-16 full scale is 32767). */
        const val SILENCE_FLOOR = 300L

        /** How much louder one channel must be to own the window outright. */
        const val DOMINANCE_RATIO = 2L

        const val MILLIS_PER_SECOND = 1000
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_FRAME = 4 // PCM-16 stereo
    }
}
