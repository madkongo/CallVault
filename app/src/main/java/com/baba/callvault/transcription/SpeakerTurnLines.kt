/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.server.speakers.SpeakerTurn

/**
 * One row per turn, not one row per sentence.
 *
 * Whisper cuts on sentences, so a person who says three of them in a row gets three rows, each repeating
 * their name. Reported by the maintainer on the OP9 on 2026-09-20 — "it would have made much more sense
 * that 0:04-0:08 is one line and 0:15-0:22 another" — on a call that also showed the second cost of short
 * rows: the last two had no name at all. That speaker's voice bled into the other channel for most of
 * them, a short row made of bleed reads as double-talk, and double-talk belongs to nobody. Joined to the
 * rows before them, the whole turn holds enough of the speaker alone to be named.
 *
 * The opposite of [SpeakerSeamSplit], and run after it: that cuts a row two people share, this joins the
 * rows one person holds. Rows are joined while
 *  - no handover sits between them — a row nobody can be named for goes with the speaker before it;
 *  - the pause between them is short, so someone who stops and starts again still gets a new row;
 *  - the joined row stays under [MAX_ROW_MS], because a row is also what a tap seeks to.
 *
 * A transcript with no speaker data is returned exactly as whisper wrote it: there is no turn to follow,
 * and those transcripts have always read as sentences.
 */
object SpeakerTurnLines {

    /** Longest joined row. Thirty seconds reads as a paragraph; five minutes is a wall, and one seek target. */
    const val MAX_ROW_MS = 30_000L

    /**
     * Longest pause joined across. The VAD's own minimum silence is 500 ms, so this joins the breaths
     * inside a turn and leaves a real stop — a second or more — as the start of a new row.
     */
    private const val MAX_PAUSE_MS = 1_000L

    fun merge(lines: List<TranscriptSegment>, turns: List<SpeakerTurn>): List<TranscriptSegment> {
        if (turns.isEmpty()) return lines

        return lines.fold(emptyList<Row>()) { rows, line ->
            val speaker = SpeakerLabeller.label(turns, line.startMs, line.endMs)
            val open = rows.lastOrNull()
            if (open != null && open.takes(line, speaker)) rows.dropLast(1) + open.joinedWith(line, speaker)
            else rows + Row(line, speaker)
        }.map { it.segment }
    }

    /** Consecutive rows one speaker held, and the last speaker named among them. */
    private data class Row(val segment: TranscriptSegment, val speaker: String?) {

        fun takes(line: TranscriptSegment, lineSpeaker: String?): Boolean {
            val isHandover = speaker != null && lineSpeaker != null && speaker != lineSpeaker
            // A negative pause is an overlap, and whisper's lines never overlap within one decode: it
            // is the segment straddling a chunk seam, which repeats the seconds the chunk before it
            // ended on. Joined, the repeat would sit inside one row with nothing to show where it begins.
            val pauseMs = line.startMs - segment.endMs
            val joinedMs = line.endMs - segment.startMs
            return !isHandover && pauseMs in 0..MAX_PAUSE_MS && joinedMs <= MAX_ROW_MS
        }

        fun joinedWith(line: TranscriptSegment, lineSpeaker: String?) = Row(
            segment = segment.copy(endMs = line.endMs, text = "${segment.text} ${line.text}"),
            speaker = lineSpeaker ?: speaker,
        )
    }
}
