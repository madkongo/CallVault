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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a long call is cut up before whisper sees it.
 *
 * The whole point is that **memory stops depending on call length**: one chunk is decoded and held at a
 * time, so a two-hour call costs what a five-minute one does. Everything here is arithmetic, which is
 * why it is tested here rather than on a phone.
 *
 * The sizing is deliberately *large*. Chunking at whisper's own 30-second window — which is what the
 * comparable project does — would remove the rolling conditioning that carries punctuation, casing and
 * proper nouns across a window boundary, and we have measured what that costs: turning conditioning off
 * fragmented a real call into 79 one-to-two-second segments and produced the *least* text of any
 * variant. Five minutes keeps whisper's own windowing intact inside a chunk, so continuity breaks four
 * times in a twenty-minute call instead of forty.
 */
class ChunkPlanTest {

    private val minute = 60_000L

    @Test
    fun `a call shorter than one chunk is left whole`() {
        // The common case by far. It must not pay any of this machinery's cost, and above all must not
        // acquire a seam it never had.
        val plan = ChunkPlan.plan(totalMs = 4 * minute)

        assertEquals(1, plan.size)
        assertEquals(0L, plan[0].decodeFromMs)
        assertEquals(4 * minute, plan[0].endMs)
        assertEquals(0L, plan[0].overlapMs)
    }

    @Test
    fun `a long call is cut into chunks that cover all of it`() {
        val total = 20 * minute
        val plan = ChunkPlan.plan(totalMs = total)

        assertTrue("expected several chunks, got ${plan.size}", plan.size > 1)
        assertEquals("the first chunk must start at the beginning", 0L, plan.first().keepFromMs)
        assertEquals("the last chunk must reach the end", total, plan.last().endMs)
        // No gaps: each chunk's kept region begins exactly where the previous one ended.
        plan.zipWithNext().forEach { (a, b) ->
            assertEquals("a gap between ${a.endMs} and ${b.keepFromMs}", a.endMs, b.keepFromMs)
        }
    }

    @Test
    fun `every chunk after the first decodes some audio before its kept region`() {
        // The overlap exists for one reason: a word straddling a cut. Decoding a little of the previous
        // chunk gives whisper the run-up, and the duplicated text is dropped afterwards by keepFromMs.
        val plan = ChunkPlan.plan(totalMs = 20 * minute)

        plan.drop(1).forEach { chunk ->
            assertTrue("chunk at ${chunk.keepFromMs} has no run-up", chunk.decodeFromMs < chunk.keepFromMs)
            assertTrue("overlap must not reach past the chunk", chunk.overlapMs > 0)
        }
    }

    @Test
    fun `no chunk is longer than the hard cap`() {
        // Peak memory is bounded by the largest chunk, so this is the property the whole feature rests
        // on. A cap that can be exceeded is not a cap.
        val plan = ChunkPlan.plan(totalMs = 120 * minute)

        plan.forEach { chunk ->
            val decoded = chunk.endMs - chunk.decodeFromMs
            assertTrue("a chunk of ${decoded}ms exceeds the cap", decoded <= ChunkPlan.MAX_CHUNK_MS)
        }
    }

    @Test
    fun `a short final chunk is absorbed rather than left on its own`() {
        // whisper is bad at very short fragments — below about a second, most outputs are a single
        // memorised word — so a 20-second tail must not become its own pass.
        val total = ChunkPlan.TARGET_CHUNK_MS + 20_000L
        val plan = ChunkPlan.plan(totalMs = total)

        assertEquals("the tail should have been absorbed into one chunk", 1, plan.size)
        assertEquals(total, plan.last().endMs)
    }

    @Test
    fun `an unknown or zero duration produces a single whole-file chunk`() {
        // Duration comes from the container and is not always declared. Refusing to plan is wrong;
        // falling back to the old whole-file behaviour is the safe direction.
        val plan = ChunkPlan.plan(totalMs = 0L)

        assertEquals(1, plan.size)
        assertEquals(0L, plan[0].decodeFromMs)
        assertEquals(0L, plan[0].endMs)
    }

    // ---- stitching ----

    @Test
    fun `the candidate parts of a segment are shifted with it`() {
        // Left behind in the chunk's own timeline they would be compared with speaker turns that are in
        // the call's, and every chunk after the first would be cut in the wrong place or not at all.
        val chunk = ChunkPlan.Chunk(decodeFromMs = 290_000, keepFromMs = 300_000, endMs = 600_000)
        val parts = listOf(TranscriptSegment(15_000, 16_000, "hel"), TranscriptSegment(17_000, 18_000, "lo"))
        val raw = listOf(TranscriptSegment(startMs = 15_000, endMs = 18_000, text = "hello", parts = parts))

        val out = ChunkPlan.stitch(raw, chunk)

        assertEquals(listOf(305_000L to 306_000L, 307_000L to 308_000L), out[0].parts.map { it.startMs to it.endMs })
    }

    @Test
    fun `a straddling segment cut at the seam says each word once, as the whole segment did`() {
        // The segment is kept whole because it ends after the seam; its parts are the same words split,
        // so cutting it must not say the part from before the seam a second time, nor lose it.
        val chunk = ChunkPlan.Chunk(decodeFromMs = 290_000, keepFromMs = 300_000, endMs = 600_000)
        val parts = listOf(TranscriptSegment(8_000, 9_500, "before the seam"), TranscriptSegment(11_000, 13_000, "after it"))
        val raw = listOf(TranscriptSegment(startMs = 8_000, endMs = 13_000, text = "before the seam after it", parts = parts))
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.B),
            SpeakerTurn(300_000, SpeakerChannel.SILENCE),
            SpeakerTurn(301_000, SpeakerChannel.A),
        )

        val rows = ChunkPlan.stitch(raw, chunk).flatMap { SpeakerSeamSplit.joinSameSpeaker(it, turns) }

        assertEquals(listOf("before the seam", "after it"), rows.map { it.text })
        assertEquals(listOf(298_000L to 299_500L, 301_000L to 303_000L), rows.map { it.startMs to it.endMs })
    }

    @Test
    fun `segment times are shifted into the whole call's timeline`() {
        // whisper reports times relative to the chunk it was given; a transcript that jumps back to
        // zero every five minutes would break every tap-to-seek in the app.
        val chunk = ChunkPlan.Chunk(decodeFromMs = 290_000, keepFromMs = 300_000, endMs = 600_000)
        val raw = listOf(TranscriptSegment(startMs = 15_000, endMs = 18_000, text = "hello"))

        val out = ChunkPlan.stitch(raw, chunk)

        assertEquals(1, out.size)
        // 290_000 (where decoding began) + 15_000 within the chunk.
        assertEquals(305_000L, out[0].startMs)
        assertEquals(308_000L, out[0].endMs)
    }

    @Test
    fun `text produced from the run-up is dropped`() {
        // The run-up was already transcribed as part of the previous chunk. Keeping it would duplicate
        // whole sentences at every seam.
        val chunk = ChunkPlan.Chunk(decodeFromMs = 290_000, keepFromMs = 300_000, endMs = 600_000)
        val raw = listOf(
            TranscriptSegment(startMs = 1_000, endMs = 4_000, text = "already said"),
            TranscriptSegment(startMs = 12_000, endMs = 15_000, text = "new"),
        )

        val out = ChunkPlan.stitch(raw, chunk)

        assertEquals(listOf("new"), out.map { it.text })
    }

    @Test
    fun `a segment straddling the seam is kept`() {
        // It ends after the seam, so it carries speech the previous chunk did not have. Dropping it
        // would lose a word at every boundary, which is the failure the overlap exists to prevent.
        val chunk = ChunkPlan.Chunk(decodeFromMs = 290_000, keepFromMs = 300_000, endMs = 600_000)
        val raw = listOf(TranscriptSegment(startMs = 8_000, endMs = 13_000, text = "across the seam"))

        val out = ChunkPlan.stitch(raw, chunk)

        assertEquals(listOf("across the seam"), out.map { it.text })
    }

    @Test
    fun `the first chunk keeps everything it produced`() {
        val chunk = ChunkPlan.Chunk(decodeFromMs = 0, keepFromMs = 0, endMs = 300_000)
        val raw = listOf(TranscriptSegment(startMs = 0, endMs = 2_000, text = "opening words"))

        assertEquals(listOf("opening words"), ChunkPlan.stitch(raw, chunk).map { it.text })
    }
}
