/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * How loud a carrier capture was, per channel, measured on the PCM the capture already holds (review N28).
 *
 * Until now only the app-call capture knew whether it had heard anything; a carrier recording of pure digital
 * zeros was stored as "Verified". This is the same detector, [CaptureSilence.SILENCE_PEAK], on the carrier paths.
 * It runs in the read/encode loop of the direct capture (the recorder process) and of the handoff encoder (the
 * app), before the stereo downmix: one pass over bytes already in memory, no second decode of the file.
 *
 * Not thread-safe: the one loop that feeds it owns it.
 */
class CaptureLevelMeter(private val channels: Int, private val sampleRate: Int) {

    private var frames = 0L
    private var peak0 = 0
    private var peak1 = 0

    /** Adds [length] bytes of interleaved little-endian PCM-16 from [pcm]. A trailing partial frame is ignored. */
    fun accept(pcm: ByteArray, length: Int) {
        val frameBytes = 2 * channels
        val whole = (minOf(length, pcm.size) / frameBytes) * frameBytes
        var i = 0
        while (i < whole) {
            val a = abs(pcm, i)
            if (a > peak0) peak0 = a
            if (channels > 1) {
                val b = abs(pcm, i + 2)
                if (b > peak1) peak1 = b
            }
            i += frameBytes
        }
        frames += whole / frameBytes
    }

    fun levels(): CaptureLevels = CaptureLevels(
        channels = channels,
        millis = if (sampleRate > 0) frames * 1000L / sampleRate else 0L,
        peak0 = peak0,
        peak1 = if (channels > 1) peak1 else NO_CHANNEL,
    )

    private fun abs(pcm: ByteArray, at: Int): Int {
        val sample = ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
        return if (sample < 0) -sample else sample
    }

    companion object {
        const val NO_CHANNEL = -1
    }
}

/** What a capture measured: its length and the loudest sample on each channel ([CaptureLevelMeter.NO_CHANNEL] for a mono one). */
data class CaptureLevels(val channels: Int, val millis: Long, val peak0: Int, val peak1: Int) {

    /** The loudest sample on any channel. */
    val loudest: Int get() = maxOf(peak0, peak1)

    /** One line that survives the recorder-to-app hop and the always-on log; [parse] reads it back. */
    fun line(): String = "$PREFIX ch=$channels ms=$millis p0=$peak0 p1=$peak1"

    companion object {
        const val PREFIX = "capture levels:"
        private val PATTERN = Regex("""capture levels: ch=(\d+) ms=(\d+) p0=(\d+) p1=(-?\d+)""")

        /** The levels line inside [text] (the recorder's diagnostics), or null when there is none or it is garbled. */
        fun parse(text: String?): CaptureLevels? {
            val m = text?.let { PATTERN.find(it) } ?: return null
            val (ch, ms, p0, p1) = m.destructured
            return runCatching { CaptureLevels(ch.toInt(), ms.toLong(), p0.toInt(), p1.toInt()) }.getOrNull()
        }

        /** [text] without its levels line, so the rest can still be logged as the capture's health. */
        fun strip(text: String): String = text.lines().filterNot { it.startsWith(PREFIX) }.joinToString("\n").trim()
    }
}

/** The judgement of a finished carrier capture's [CaptureLevels]. */
object CaptureSilence {

    /**
     * A sample louder than this (of 32768) is sound. The same 100 the app-call capture uses for its far side, about
     * -50 dBFS: a silenced stream is EXACTLY zero, so this only guards against dither. Judged on the PEAK of the whole
     * call, never an average, so one syllable, a whisper (peaks in the hundreds to thousands) or a quiet far side is
     * enough: only a capture that never rose above noise-floor dither on any channel is called silent.
     */
    const val SILENCE_PEAK = 100

    /** Shorter captures are not judged: a call dropped after two seconds may legitimately hold nothing. */
    const val MIN_JUDGED_MILLIS = 5_000L

    enum class Verdict {
        /** Something was heard. */
        SOUND,

        /** Every channel stayed at or under [SILENCE_PEAK] for a judged length of time. */
        NOTHING_HEARD,

        /** Not measured (older recorder, scrcpy path) or too short to judge. Never read as silence. */
        UNKNOWN,
    }

    fun judge(levels: CaptureLevels?): Verdict = when {
        levels == null -> Verdict.UNKNOWN
        levels.millis < MIN_JUDGED_MILLIS -> Verdict.UNKNOWN
        levels.loudest > SILENCE_PEAK -> Verdict.SOUND
        else -> Verdict.NOTHING_HEARD
    }
}
