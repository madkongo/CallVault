/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * How a long call is cut up before whisper sees it, and how the pieces are put back together.
 *
 * **What this buys.** Transcription decoded the whole call into memory first, so cost grew with length
 * until the heap ran out — which is what `TranscriptionLengthLimit` exists to refuse in advance. Decode
 * one chunk at a time and peak memory stops depending on call length: a two-hour call costs what a
 * five-minute one does, and the limit can eventually go.
 *
 * **Why the chunks are large.** The obvious size is whisper's own 30-second window, and that is what
 * the comparable open-source project uses. It would be a mistake here. whisper carries text state
 * across its internal windows — punctuation, casing, proper nouns, and the thread of a sentence — and
 * we have measured what removing that costs: with conditioning disabled, a real call fragmented into
 * **79 segments of one to two seconds** and produced the *least* text of any variant tested. That
 * project transcribes short voice notes, where there is nothing to carry. We transcribe conversations.
 *
 * Five minutes keeps whisper's own windowing and conditioning intact *inside* a chunk, so continuity
 * breaks four times in a twenty-minute call rather than forty.
 *
 * Pure arithmetic, deliberately: no Android, no audio, so every boundary rule is testable.
 */
object ChunkPlan {

    /**
     * Target audio per whisper pass.
     *
     * Five minutes of 16 kHz mono float is about 19 MB on the Java heap, plus the decoder's own
     * buffers — comfortably bounded whatever the call length, and long enough that the seams are rare.
     */
    const val TARGET_CHUNK_MS = 5 * 60 * 1000L

    /** Hard cap on what one pass may decode, run-up included. Peak memory rests on this. */
    const val MAX_CHUNK_MS = 6 * 60 * 1000L

    /**
     * How much of the previous chunk is decoded again as a run-up.
     *
     * Not for stitching text — for giving whisper something to hear before the words we intend to
     * keep. A cut lands wherever five minutes happens to fall, quite possibly mid-word; without a
     * run-up the first utterance of every chunk is decoded from a standing start. The duplicate text
     * this produces is discarded by [stitch].
     */
    const val OVERLAP_MS = 10_000L

    /**
     * Shortest a final chunk may be before it is absorbed into its predecessor.
     *
     * whisper handles short fragments badly — below roughly a second, most outputs are a single
     * memorised word — and a 20-second tail given its own pass is a transcript ending in noise.
     */
    const val MIN_TAIL_MS = 45_000L

    /**
     * One pass over the audio.
     *
     * @param decodeFromMs where decoding starts, including the run-up. What the decoder is asked for.
     * @param keepFromMs where the text is trusted from. Anything before this was already transcribed
     *   as part of the previous chunk.
     * @param endMs where this pass stops. 0 with [decodeFromMs] 0 means "the whole file", used when the
     *   container declares no duration.
     */
    data class Chunk(val decodeFromMs: Long, val keepFromMs: Long, val endMs: Long) {
        /** How much audio precedes [keepFromMs]; 0 for the first chunk. */
        val overlapMs: Long get() = keepFromMs - decodeFromMs
    }

    /**
     * Cuts a call of [totalMs] into passes.
     *
     * An unknown length — 0, which is what a container declaring no duration reports — yields a single
     * whole-file chunk, exactly the behaviour that existed before chunking. Falling back to what
     * already worked is the safe direction; refusing to plan would turn a missing metadata field into
     * a missing transcript.
     */
    fun plan(
        totalMs: Long,
        targetMs: Long = TARGET_CHUNK_MS,
        overlapMs: Long = OVERLAP_MS,
        minTailMs: Long = MIN_TAIL_MS,
    ): List<Chunk> {
        if (totalMs <= 0L || totalMs <= targetMs) return listOf(Chunk(0L, 0L, totalMs))

        val chunks = mutableListOf<Chunk>()
        var cursor = 0L
        while (cursor < totalMs) {
            var end = minOf(cursor + targetMs, totalMs)
            // Absorb a short tail rather than leaving it to be decoded on its own.
            if (totalMs - end in 1 until minTailMs) end = totalMs
            val decodeFrom = if (cursor == 0L) 0L else maxOf(0L, cursor - overlapMs)
            chunks += Chunk(decodeFromMs = decodeFrom, keepFromMs = cursor, endMs = end)
            cursor = end
        }
        return chunks
    }

    /**
     * Moves [segments] from chunk-relative time into the call's timeline, dropping what the run-up
     * already produced.
     *
     * A segment that *straddles* the seam is kept: it ends after [Chunk.keepFromMs], so it carries
     * speech the previous chunk did not have. Dropping it would lose a word at every boundary, which
     * is the failure the run-up exists to prevent in the first place.
     */
    fun stitch(segments: List<TranscriptSegment>, chunk: Chunk): List<TranscriptSegment> =
        segments.asSequence()
            .map {
                it.copy(
                    startMs = it.startMs + chunk.decodeFromMs,
                    endMs = it.endMs + chunk.decodeFromMs,
                    parts = it.parts.map { part ->
                        part.copy(
                            startMs = part.startMs + chunk.decodeFromMs,
                            endMs = part.endMs + chunk.decodeFromMs,
                        )
                    },
                )
            }
            .filter { it.endMs > chunk.keepFromMs }
            .toList()
}
