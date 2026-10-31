/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.transcription.DecodeStall.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeStallTest {

    private val limit = DecodeStall.LIMIT_MS

    @Test
    fun `output resets the clock to now`() {
        val verdict = DecodeStall.next(lastProgressMs = 0L, nowMs = 5_000L, madeProgress = true, reachedEnd = false)
        assertEquals(Verdict.Continue(5_000L), verdict)
    }

    @Test
    fun `a quiet turn within the limit keeps the old clock`() {
        val verdict = DecodeStall.next(lastProgressMs = 1_000L, nowMs = 1_000L + limit, madeProgress = false, reachedEnd = false)
        assertEquals(Verdict.Continue(1_000L), verdict)
    }

    @Test
    fun `no output past the limit is a stall`() {
        val verdict = DecodeStall.next(lastProgressMs = 1_000L, nowMs = 1_001L + limit, madeProgress = false, reachedEnd = false)
        assertEquals(Verdict.Stalled(limit + 1), verdict)
    }

    @Test
    fun `end of stream finishes normally even after a long quiet spell`() {
        val verdict = DecodeStall.next(lastProgressMs = 0L, nowMs = 10 * limit, madeProgress = false, reachedEnd = true)
        assertEquals(Verdict.Finished, verdict)
    }

    @Test
    fun `a slow decode that keeps producing output is never failed, however long it runs`() {
        // Four hours of decoding with output every 100 s — slower than any real phone, still progress.
        var last = 0L
        var now = 0L
        val fourHoursMs = 4 * 60 * 60 * 1000L
        while (now < fourHoursMs) {
            now += 100_000L
            val verdict = DecodeStall.next(last, now, madeProgress = true, reachedEnd = false)
            assertTrue(verdict is Verdict.Continue)
            last = (verdict as Verdict.Continue).lastProgressMs
            // ...with quiet turns in between that must not trip it either.
            assertTrue(DecodeStall.next(last, now + 99_000L, madeProgress = false, reachedEnd = false) is Verdict.Continue)
        }
    }

    @Test
    fun `a stall after earlier progress is measured from the last output, not from the start`() {
        val afterOutput = DecodeStall.next(0L, 60_000L, madeProgress = true, reachedEnd = false) as Verdict.Continue
        assertTrue(DecodeStall.next(afterOutput.lastProgressMs, 60_000L + limit, false, false) is Verdict.Continue)
        assertTrue(DecodeStall.next(afterOutput.lastProgressMs, 60_001L + limit, false, false) is Verdict.Stalled)
    }

    @Test
    fun `the stall is an IllegalStateException so existing failure paths treat it as a failed decode`() {
        assertTrue(DecodeStalledException("x") is IllegalStateException)
    }
}
