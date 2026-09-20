/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.server.speakers.SpeakerTurn
import com.baba.callvault.transcription.SpeechGapSnap.Speech

/**
 * Cuts a transcript line both people share at the pause where one hands over to the other.
 *
 * Whisper cuts on its own idea of a sentence, not on who is talking. Measured on the OP9 on
 * 2026-09-20: one person spoke 4.9–8.7 s, the other 11–15 s, the VAD kept a stretch for each — and
 * whisper, in Hebrew, returned both as ONE segment. [SpeakerLabeller] rightly names nobody for a line
 * two people share, so the transcript lost every label it had had under English, where the same audio
 * came back as two segments.
 *
 * ## Only at a pause the VAD already found, and only between two named, different speakers
 *
 * Whisper's own length split (`max_len`) was measured first and cuts by character count: 13.3 s and
 * 7.7 s on that call, nowhere near the change at 9–11 s. Its token times, though, are accurate — on
 * the VAD's *compressed* timeline, where the silence has been removed. So each word is placed in the
 * kept stretch its time falls in, and a line is cut at the seam between two stretches **when the
 * labeller names a different side for each**.
 *
 * Deliberately not per-word labelling. A seam is a pause of at least the VAD's minimum silence, which is
 * what a change of speaker looks like, and it needs no new threshold. The same speaker on both sides —
 * someone who stopped to think — is not cut, which is what keeps this from shredding a transcript. A
 * rapid exchange with no pause stays one shared line, exactly as it was, so nothing gets worse.
 *
 * Pure arithmetic, so it is tested against the measured call without a device or a model.
 */
object SpeakerSeamSplit {

    /**
     * What whisper.cpp puts between two kept stretches when it rebuilds the audio. Hardcoded there, so it
     * is a constant here; the same 100 ms [SpeechGapSnap] exists to undo for whole segments.
     */
    private const val BRIDGE_MS = 100L

    /** One word of a segment, with when it starts on the VAD's compressed timeline. */
    data class Word(val text: String, val compressedStartMs: Long)

    /**
     * [segment] as one piece per run of stretches a single speaker held, or unchanged.
     *
     * @param words the segment's words in order, on the compressed timeline. Empty when token times
     *   are unavailable, which leaves the segment alone.
     * @param speech what the VAD kept, in ORIGINAL time. Fewer than two leaves nothing to cut at.
     * @param turns who was talking when, in original time.
     */
    fun split(
        segment: TranscriptSegment,
        words: List<Word>,
        speech: List<Speech>,
        turns: List<SpeakerTurn>,
    ): List<TranscriptSegment> {
        if (words.isEmpty() || speech.size < 2 || turns.isEmpty()) return listOf(segment)

        val ordered = speech.sortedBy { it.startMs }
        val speakerOf = ordered.map { SpeakerLabeller.label(turns, it.startMs, it.endMs) }
        val stretchOf = words.map { stretchIndexAt(it.compressedStartMs, ordered) }

        val pieces = mutableListOf<List<Int>>()
        var current = mutableListOf(0)
        for (i in 1 until words.size) {
            if (isHandover(stretchOf[i - 1], stretchOf[i], speakerOf)) {
                pieces += current
                current = mutableListOf()
            }
            current += i
        }
        pieces += current
        if (pieces.size == 1) return listOf(segment)

        return pieces.map { indices ->
            TranscriptSegment(
                startMs = maxOf(segment.startMs, ordered[stretchOf[indices.first()]].startMs),
                endMs = minOf(segment.endMs, ordered[stretchOf[indices.last()]].endMs),
                text = indices.joinToString(" ") { words[it].text },
            )
        }
    }

    /** Whether the step from one word's stretch to the next crosses from one named speaker to another. */
    private fun isHandover(from: Int, to: Int, speakerOf: List<String?>): Boolean {
        if (from == to) return false
        val before = speakerOf[from] ?: return false
        val after = speakerOf[to] ?: return false
        return before != after
    }

    /**
     * Which kept stretch a compressed-timeline moment falls in.
     *
     * The bridge after a stretch belongs to that stretch: a word whisper timed inside it was spoken at
     * the end of what came before, not at the start of what comes next.
     */
    private fun stretchIndexAt(compressedMs: Long, speech: List<Speech>): Int {
        var stretchStart = 0L
        speech.forEachIndexed { index, stretch ->
            val nextStart = stretchStart + (stretch.endMs - stretch.startMs) + BRIDGE_MS
            if (compressedMs < nextStart) return index
            stretchStart = nextStart
        }
        return speech.lastIndex
    }
}
