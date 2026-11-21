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
 * 7.7 s on that call, nowhere near the change at 9–11 s. Its token times, though, are accurate, and
 * whisper.cpp maps them back to original time itself. So each word is placed in the kept stretch its
 * time falls in (or the nearer one, for a word timed inside a removed pause), and a line is cut at the seam between two stretches **when the
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
     * One word of a segment, with when it starts in ORIGINAL audio time. The native layer reads it with
     * `whisper_full_get_token_t0`, which maps the VAD's compressed time back over each kept stretch's exact
     * bounds, so nothing here has to re-derive whisper's layout (C151: the old rebuild assumed 100 ms
     * between stretches where whisper puts its sample overlap plus 100 ms, and drifted).
     */
    data class Word(val text: String, val startMs: Long)

    /**
     * [segment] as one piece per run of stretches a single speaker held, or unchanged.
     *
     * The whole rule in one call, which is what the tests measure against the real call. The app runs it
     * as its two halves, [atSeams] in the engine and [joinSameSpeaker] in the runner, because the engine
     * decodes chunk by chunk before the speaker turns exist.
     *
     * @param words the segment's words in order, in original time. Empty when token times
     *   are unavailable, which leaves the segment alone.
     * @param speech what the VAD kept, in ORIGINAL time. Fewer than two leaves nothing to cut at.
     * @param turns who was talking when, in original time.
     */
    fun split(
        segment: TranscriptSegment,
        words: List<Word>,
        speech: List<Speech>,
        turns: List<SpeakerTurn>,
    ): List<TranscriptSegment> = joinSameSpeaker(atSeams(segment, words, speech), turns)

    /**
     * The engine's half: [segment] with its [TranscriptSegment.parts] filled in, one per kept stretch it
     * has words in. Knows nothing about speakers. Unchanged when there is no pause inside the segment.
     */
    fun atSeams(segment: TranscriptSegment, words: List<Word>, speech: List<Speech>): TranscriptSegment {
        if (words.isEmpty() || speech.size < 2) return segment

        val ordered = speech.sortedBy { it.startMs }
        val byStretch = words.groupBy { stretchIndexAt(it.startMs, ordered) }.toSortedMap()
        if (byStretch.size < 2) return segment

        val parts = byStretch.map { (index, spoken) ->
            TranscriptSegment(
                startMs = maxOf(segment.startMs, ordered[index].startMs),
                endMs = minOf(segment.endMs, ordered[index].endMs),
                text = spoken.joinToString(" ") { it.text },
            )
        }
        return segment.copy(parts = parts)
    }

    /**
     * The runner's half: the candidates one speaker held joined back together, the cut kept only where
     * [SpeakerLabeller] names a different side before and after it. Never returns leftover candidates.
     */
    fun joinSameSpeaker(segment: TranscriptSegment, turns: List<SpeakerTurn>): List<TranscriptSegment> {
        val whole = segment.copy(parts = emptyList())
        if (segment.parts.size < 2 || turns.isEmpty()) return listOf(whole)

        // A run is compared by the last speaker NAMED in it, not by its neighbour: B, then a stretch of
        // double-talk nobody can be named for, then A, is still a handover from B to A.
        val runs = segment.parts.fold(emptyList<Run>()) { runs, part ->
            val speaker = SpeakerLabeller.label(turns, part.startMs, part.endMs)
            val open = runs.lastOrNull()
            if (open == null || isHandover(open.speaker, speaker)) runs + Run(part, speaker)
            else runs.dropLast(1) + open.joinedWith(part, speaker)
        }
        val pieces = runs.map { it.segment }
        return if (pieces.size == 1) listOf(whole) else pieces
    }

    /** Parses the native layer's words: one per line, `<original start in ms>\t<text>`. Bad lines are skipped. */
    fun parseWords(encoded: String): List<Word> =
        encoded.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val startMs = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
            val text = line.substring(tab + 1).trim()
            if (text.isEmpty()) null else Word(text, startMs)
        }.toList()

    /** Consecutive candidates held by one speaker, and the last speaker named among them. */
    private data class Run(val segment: TranscriptSegment, val speaker: String?) {
        fun joinedWith(part: TranscriptSegment, partSpeaker: String?) = Run(
            segment = segment.copy(endMs = part.endMs, text = "${segment.text} ${part.text}"),
            speaker = partSpeaker ?: speaker,
        )
    }

    /** Whether a run and the candidate after it belong to two named, different speakers. */
    private fun isHandover(before: String?, after: String?): Boolean =
        before != null && after != null && before != after

    /**
     * Which kept stretch an original-time moment falls in: the one containing it, else the nearer one
     * (the earlier on a tie). Past either end it is the first or last.
     */
    private fun stretchIndexAt(ms: Long, speech: List<Speech>): Int =
        speech.indices.minByOrNull { i ->
            val stretch = speech[i]
            when {
                ms < stretch.startMs -> stretch.startMs - ms
                ms > stretch.endMs -> ms - stretch.endMs
                else -> 0L
            }
        } ?: 0
}
