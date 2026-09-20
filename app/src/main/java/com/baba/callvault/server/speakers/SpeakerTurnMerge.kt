/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

/**
 * Stitches per-chunk speaker turns into one timeline (issue #38).
 *
 * ## Why this is needed offline and not live
 *
 * [SpeakerTurnDetector] counts its own 100 ms windows from zero, which is exactly right when it watches
 * a call once, live, from the first sample. Offline the call is read back in **chunks** — transcription
 * already decodes it that way — and a fresh detector per chunk starts its clock at zero again. Each
 * chunk's turns therefore have to be moved to where that chunk really began.
 *
 * The chunks are not tidy either. `AudioDecoder.decodeRange` seeks to the previous sync point, so a
 * chunk can begin *earlier* than it was asked for and overlap its predecessor — which is why it reports
 * its real start rather than the requested one. Stitched naively that gives duplicate turns and a
 * timeline that walks backwards.
 *
 * ## The rules
 *
 * - Every chunk's turns are offset by that chunk's real start.
 * - Chunks are sorted by start, so they may be handed over in any order.
 * - Anything at or before the last turn already accepted is dropped — an overlap re-hears audio that is
 *   already represented, and re-inserting it would move the timeline backwards.
 * - A run of the same speaker across a seam collapses to one turn, so the reading view does not draw a
 *   speaker change where the speaker never changed.
 *
 * Free of Android types so every branch is tested.
 */
object SpeakerTurnMerge {

    /**
     * @param chunks each chunk's real start in milliseconds, paired with the turns its own detector
     *   produced (whose `startMs` are relative to that chunk).
     * @return one ordered, coalesced timeline in absolute milliseconds.
     */
    fun stitch(chunks: List<Pair<Long, List<SpeakerTurn>>>): List<SpeakerTurn> {
        val out = mutableListOf<SpeakerTurn>()

        chunks.sortedBy { it.first }.forEach { (startMs, turns) ->
            turns.forEach { turn ->
                val at = startMs + turn.startMs
                val last = out.lastOrNull()

                // An overlap re-hears audio we already have. Dropping it keeps the timeline monotonic;
                // accepting it would let a later chunk rewrite an earlier answer.
                if (last != null && at <= last.startMs) return@forEach

                // The speaker did not change across the seam, so neither should the timeline.
                if (last != null && last.channel == turn.channel) return@forEach

                out += SpeakerTurn(startMs = at, channel = turn.channel)
            }
        }

        return out
    }
}
