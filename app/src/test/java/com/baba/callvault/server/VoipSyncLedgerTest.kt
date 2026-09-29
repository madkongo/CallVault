/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync ledger of a VoIP capture: what it counts about the two sides and how it reports the
 * offset between them, so that one call's log from a phone we do not have (issue #41) says which
 * side moved, by how much, and why.
 */
class VoipSyncLedgerTest {

    private val rate = 48_000
    private val chunkFrames = 960

    @Test
    fun `a chunk's content time comes from the HAL timestamp when there is one`() {
        // The HAL said frame 48_000 was captured at t=10 s; the chunk starting at frame 96_000 is
        // therefore 1 s of audio later.
        val side = VoipSyncLedger.Side("near", rate)
        side.timestamp(framePosition = 48_000, nanos = 10_000_000_000L)
        assertEquals(11_000_000_000L, side.contentNanos(frameIndex = 96_000, readAtNanos = 99L))
        assertEquals("hal", side.timeSource)
    }

    @Test
    fun `without a HAL timestamp the read time stands in, and the report says so`() {
        val side = VoipSyncLedger.Side("far", rate)
        assertEquals(5_000L, side.contentNanos(frameIndex = 96_000, readAtNanos = 5_000L))
        assertEquals("read", side.timeSource)
    }

    @Test
    fun `a fresh record without a fix yet uses the read latency the side has already learned`() {
        // A re-taken record has no HAL fix for its first moments, and its read moment runs a
        // buffer late; on the reporter's phone that was 155 records in two minutes. The latency of
        // a source with the same buffer is the same, so the last one learned stands in.
        val side = VoipSyncLedger.Side("near", rate)
        side.timestamp(framePosition = 0, nanos = 10_000_000_000L)
        // Chunk at frame 48_000 has content time 11.0 s; it was read at 11.16 s → latency 160 ms.
        side.contentNanos(frameIndex = 48_000, readAtNanos = 11_160_000_000L)
        side.newRecord()
        assertEquals(20_000_000_000L, side.contentNanos(frameIndex = 0, readAtNanos = 20_160_000_000L))
        assertEquals("read-", side.timeSource)
    }

    @Test
    fun `chunks discarded for being older than their slot are counted`() {
        val ledger = VoipSyncLedger(rate, chunkFrames)
        ledger.far.discarded(); ledger.far.discarded()
        assertEquals(2, ledger.far.chunksDiscarded)
        assertTrue(ledger.snapshot(0, 0, 0, 0).contains("disc=2"))
    }

    @Test
    fun `the offset is near content time minus far content time, in the file's own frame`() {
        // Sign convention, fixed here because everything downstream reads it: POSITIVE means the
        // near audio at a file position is NEWER than the far audio beside it — the far audio has
        // moved LATER in the file, the far party sounds late. Issue #41 (far party early) is
        // NEGATIVE. Got wrong once in the first draft; see VoipSyncLedger's header for the worked case.
        val ledger = VoipSyncLedger(rate, chunkFrames)
        ledger.paired(nearContentNanos = 2_000_000_000L, farContentNanos = 1_500_000_000L)
        assertEquals(500L, ledger.lastOffsetMs)
        ledger.paired(nearContentNanos = 3_000_000_000L, farContentNanos = 3_100_000_000L)
        assertEquals(-100L, ledger.lastOffsetMs)
        assertEquals(-100L, ledger.minOffsetMs)
        assertEquals(500L, ledger.maxOffsetMs)
    }

    @Test
    fun `a pair with a silence stand-in has no offset and does not disturb the extremes`() {
        val ledger = VoipSyncLedger(rate, chunkFrames)
        ledger.paired(nearContentNanos = 2_000_000_000L, farContentNanos = null)
        assertNull(ledger.lastOffsetMs)
        assertNull(ledger.minOffsetMs)
    }

    @Test
    fun `every kind of loss is counted per side`() {
        val ledger = VoipSyncLedger(rate, chunkFrames)
        ledger.near.read(); ledger.near.read(); ledger.near.read()
        ledger.far.read()
        ledger.near.dropped()
        ledger.far.substituted(); ledger.far.substituted()
        ledger.near.zeroChunk(); ledger.near.retake(gapNanos = 600_000_000L)
        assertEquals(3, ledger.near.chunksRead)
        assertEquals(1, ledger.near.chunksDropped)
        assertEquals(2, ledger.far.chunksSubstituted)
        assertEquals(1, ledger.near.zeroChunks)
        assertEquals(1, ledger.near.retakes)
        assertEquals(600L, ledger.near.retakeGapTotalMs)
    }

    @Test
    fun `each re-take records what Android said about the silencing`() {
        // Backlog #9, step 1 — evidence only, behaviour unchanged. The re-take fires on 15 zero chunks;
        // Android's own answer (isClientSilenced) says whether that was real silencing or a pause. The
        // count decides, per phone, whether the re-take can later be skipped for pauses.
        val ledger = VoipSyncLedger(rate, chunkFrames)
        ledger.near.retake(gapNanos = 100_000_000L, platformSilenced = true)
        ledger.near.retake(gapNanos = 100_000_000L, platformSilenced = false)
        ledger.near.retake(gapNanos = 100_000_000L, platformSilenced = false)
        ledger.near.retake(gapNanos = 100_000_000L, platformSilenced = null)
        assertEquals(4, ledger.near.retakes)
        assertEquals(1, ledger.near.retakesSilenced)
        assertEquals(2, ledger.near.retakesNotSilenced)
        assertEquals(1, ledger.near.retakesUnknown)
        assertTrue(ledger.near.format(0).contains("retake=4/400ms(silenced=1 quiet=2 unknown=1)"))
    }

    @Test
    fun `the longest stall on each side is kept, not just the count`() {
        // Ten stand-ins in a row is one 1.2 s stall (20 ms each at a 120 ms wait); ten spread over
        // a call is jitter. The report needs to tell them apart.
        val ledger = VoipSyncLedger(rate, chunkFrames)
        repeat(3) { ledger.near.substituted() }
        ledger.near.read()
        repeat(5) { ledger.near.substituted() }
        ledger.near.read()
        assertEquals(5, ledger.near.longestStallChunks)
    }

    @Test
    fun `the snapshot line carries everything a reader needs, on one line`() {
        val ledger = VoipSyncLedger(rate, chunkFrames)
        ledger.near.read(); ledger.far.read()
        ledger.paired(nearContentNanos = 1_050_000_000L, farContentNanos = 1_000_000_000L)
        val line = ledger.snapshot(fileFrames = 48_000L, wallNanos = 1_100_000_000L, nearQueued = 1, farQueued = 7)
        listOf("t=1.0s", "wall=1.1s", "offset=+50ms", "near{", "far{", "read=1", "q=7", "sub=0", "drop=0").forEach {
            assertTrue("snapshot must carry '$it': $line", line.contains(it))
        }
    }

    @Test
    fun `the summary names the offset's drift over the call`() {
        val ledger = VoipSyncLedger(rate, chunkFrames)
        ledger.paired(nearContentNanos = 1_000_000_000L, farContentNanos = 1_000_000_000L)
        ledger.paired(nearContentNanos = 61_000_000_000L, farContentNanos = 60_200_000_000L)
        val line = ledger.summary(fileFrames = 48_000L * 60, wallNanos = 60_500_000_000L)
        listOf("first=+0ms", "last=+800ms", "min=+0ms", "max=+800ms", "file=60.0s", "wall=60.5s").forEach {
            assertTrue("summary must carry '$it': $line", line.contains(it))
        }
    }
}
