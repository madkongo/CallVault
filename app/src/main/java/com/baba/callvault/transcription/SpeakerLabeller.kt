/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.server.speakers.SpeakerChannel
import com.baba.callvault.server.speakers.SpeakerTurn
import com.baba.callvault.server.speakers.SpeakerTurnCodec

/**
 * Attributes each transcribed segment to a capture channel.
 *
 * Two clocks meet here, and they are the same clock: whisper reports segment times relative to the
 * start of the decoded file, and the turns were accumulated from the same capture loop that wrote
 * it, so both count from the first captured sample.
 *
 * **A and B, never You and Them.** Which channel carries the near party is an OEM detail resolved
 * separately and applied when a transcript is *displayed* — see
 * [com.baba.callvault.data.ChannelMap]. Storing a side here can never turn out to be wrong about
 * who is who; the worst case is that the labels stay neutral forever.
 *
 * **Null is a first-class answer.** Whisper cuts on pauses, not on turn boundaries, so a segment can
 * genuinely belong to both sides or to neither. Leaving it unlabelled reads as ordinary timestamped
 * text; guessing puts one person's words in the other's mouth on a record of a real conversation.
 */
object SpeakerLabeller {

    /**
     * How much of the time the two sides spoke ALONE one of them must hold to be named.
     *
     * Two thirds rather than a bare majority: a segment split 55/45 across a handover is not
     * evidence of anything, and there is no cost to leaving it neutral.
     */
    private const val DOMINANCE_FRACTION = 0.66

    /**
     * How much of a segment's voiced time the winner must hold **alone**, whatever else is true.
     *
     * BOTH is not always two people. On a Shizuku recording one voice reaches the other channel at
     * about half level — far-end echo, or a test with both phones in one room — and those windows read
     * as double-talk. Measured on the OP9 on 2026-09-20: 3.0 s alone, 2.1 s "both", the other side
     * 0.0 s, so 59% of voiced and no label for a segment only one person spoke in. Dominance is
     * therefore judged between the two sides' time ALONE, and this floor keeps a segment that is nearly
     * all double-talk from being named after a sliver.
     *
     * Strictly looser than judging against all voiced time, which is what this used to do: every
     * label the old rule gave is still given, and only some nulls become names.
     */
    private const val MIN_ALONE_FRACTION = 0.33

    /** Decodes stored turns, yielding an empty list for anything unreadable. */
    fun decode(encoded: String): List<SpeakerTurn> = SpeakerTurnCodec.decode(encoded)

    /**
     * The side that did most of the talking between [startMs] and [endMs], or null.
     *
     * @return [SpeakerChannel.A] or [SpeakerChannel.B]'s key, or null when the segment was shared,
     *   silent, or falls outside anything that was recorded.
     */
    fun label(turns: List<SpeakerTurn>, startMs: Long, endMs: Long): String? {
        if (turns.isEmpty() || endMs <= startMs) return null

        var a = 0L
        var b = 0L
        var both = 0L

        turns.forEachIndexed { index, turn ->
            // Turns are contiguous, so a turn runs until the next one starts — and the last one runs
            // to the end of the recording, whenever that was.
            val turnEnd = turns.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
            val overlap = minOf(turnEnd, endMs) - maxOf(turn.startMs, startMs)
            if (overlap <= 0) return@forEachIndexed

            when (turn.channel) {
                SpeakerChannel.A -> a += overlap
                SpeakerChannel.B -> b += overlap
                SpeakerChannel.BOTH -> both += overlap
                // Silence belongs to nobody, and it is most of a call. Counting it would leave every
                // segment with a pause in it below the threshold and therefore unlabelled.
                SpeakerChannel.SILENCE -> Unit
            }
        }

        val voiced = a + b + both
        if (voiced <= 0) return null

        val winner = maxOf(a, b)
        if (winner < DOMINANCE_FRACTION * (a + b)) return null
        if (winner < MIN_ALONE_FRACTION * voiced) return null

        return if (a > b) SpeakerChannel.A.key else SpeakerChannel.B.key
    }

    /**
     * Labels a whole transcript in one pass.
     *
     * @param windows each segment's start and end, in order.
     * @return one label per window, positionally — null where no side was dominant.
     */
    fun labelAll(turns: List<SpeakerTurn>, windows: List<Pair<Long, Long>>): List<String?> =
        windows.map { (startMs, endMs) -> label(turns, startMs, endMs) }
}
