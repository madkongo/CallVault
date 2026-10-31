/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import com.baba.callvault.transcription.ChunkPlan
import com.baba.callvault.transcription.TranscriptSegment

/**
 * Joins the passes of a cloud transcription so the seam has neither a cut word nor a repeated one.
 *
 * [ChunkPlan.stitch] keeps any segment that straddles the seam, from both sides. That suits whisper.cpp
 * on the phone, which sees the run-up and the chunk as one continuous decode; a cloud service hears each
 * pass as a separate file, so the last segment of pass N is cut off at the chunk end ("…לוינטר כ") and
 * pass N+1 says it again ("לוינטר כבר היום…") — measured on the OP9, 2026-10-29: pass 1 ended
 * 294180–300020, pass 2 began 299193–300952.
 *
 * So:
 *  1. a segment of pass N that runs to the end of its audio (within [EDGE_MS]) was cut, and is dropped —
 *     but only when the next pass has words to replace it;
 *  2. pass N+1 starts early, by [CLOUD_OVERLAP_MS] of run-up, so it hears that dropped segment whole;
 *  3. of pass N+1, only segments whose middle lies after the last kept segment are taken.
 *
 * Pure arithmetic, so the seam rule is tested with the measured numbers.
 */
object CloudSeam {

    /**
     * Run-up for a cloud pass. Longer than on the phone (10 s): whisper's segments run up to 30 s, and
     * the run-up must hold the whole of a segment rule 1 dropped, or its start would be lost.
     */
    const val CLOUD_OVERLAP_MS = 30_000L

    /** How close to the end of a pass a segment must reach to count as cut off. */
    const val EDGE_MS = 1_000L

    /** One pass's segments, already on the call's timeline, and the chunk they came from. */
    data class Pass(val chunk: ChunkPlan.Chunk, val segments: List<TranscriptSegment>)

    fun join(passes: List<Pass>): List<TranscriptSegment> {
        val result = mutableListOf<TranscriptSegment>()
        var previous: ChunkPlan.Chunk? = null
        for (pass in passes) {
            val prior = previous
            if (prior == null) {
                result += pass.segments
            } else if (pass.segments.isNotEmpty()) {
                // Rule 1: the previous pass's tail was cut at its end; this pass heard it whole.
                while (result.isNotEmpty() && prior.endMs > 0 && result.last().endMs >= prior.endMs - EDGE_MS) {
                    result.removeAt(result.lastIndex)
                }
                // Rule 3: of this pass, only what follows what is already kept.
                val cutoff = result.lastOrNull()?.endMs ?: Long.MIN_VALUE
                result += pass.segments.filter { (it.startMs + it.endMs) / 2 >= cutoff }
            }
            previous = pass.chunk
        }
        return result
    }
}
