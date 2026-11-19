/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.VoipRingMemory.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** BabApps item 113: what the ring listener heard before the pickup. Timings follow the OP9 probe (2026-11-17). */
class VoipRingMemoryTest {

    private val wa = "com.whatsapp"
    private val tg = "org.telegram.messenger"

    @After fun reset() = VoipRingMemory.clearForTest()

    @Test
    fun `nothing heard is none`() {
        assertEquals(Verdict.NONE, VoipRingMemory.verdict(wa, 1_000))
    }

    @Test
    fun `WhatsApp rings, then the same notification is rewritten as ongoing at the pickup`() {
        VoipRingMemory.onPosted(wa, "0|wa|23", incomingMarker = true, ongoing = true, groupSummary = false, now = 1_000)
        assertEquals(Verdict.UNRESOLVED_RING, VoipRingMemory.verdict(wa, 4_000))
        VoipRingMemory.onPosted(wa, "0|wa|23", incomingMarker = false, ongoing = true, groupSummary = false, now = 8_000)
        assertEquals(Verdict.ANSWERED_RING, VoipRingMemory.verdict(wa, 8_300))
    }

    @Test
    fun `Telegram removes the ringing notification and posts the ongoing one a moment later`() {
        VoipRingMemory.onPosted(tg, "0|tg|203", true, ongoing = false, groupSummary = false, now = 1_000)
        VoipRingMemory.onRemoved(tg, "0|tg|203", 3_000)
        VoipRingMemory.onPosted(tg, "0|tg|201", false, ongoing = true, groupSummary = false, now = 3_400)
        assertEquals(Verdict.ANSWERED_RING, VoipRingMemory.verdict(tg, 5_000))
    }

    @Test
    fun `a declined ring is not an answer, and a call placed at once from the same app is not incoming`() {
        VoipRingMemory.onPosted(tg, "0|tg|203", true, ongoing = false, groupSummary = false, now = 1_000)
        VoipRingMemory.onRemoved(tg, "0|tg|203", 3_000)
        // Ongoing notification of a call placed 5 s later: past the hand-over window.
        VoipRingMemory.onPosted(tg, "0|tg|201", false, ongoing = true, groupSummary = false, now = 8_000)
        assertEquals(Verdict.UNRESOLVED_RING, VoipRingMemory.verdict(tg, 9_000))
        // And after the unresolved time has passed it is gone.
        assertEquals(Verdict.NONE, VoipRingMemory.verdict(tg, 3_000 + VoipRingMemory.UNRESOLVED_MS + 1))
    }

    @Test
    fun `a group summary or a notification of another app is not an answer`() {
        VoipRingMemory.onPosted(wa, "0|wa|23", true, ongoing = true, groupSummary = false, now = 1_000)
        VoipRingMemory.onPosted(wa, "0|wa|ranker", false, ongoing = true, groupSummary = true, now = 2_000)
        VoipRingMemory.onPosted(tg, "0|tg|201", false, ongoing = true, groupSummary = false, now = 2_100)
        assertEquals(Verdict.UNRESOLVED_RING, VoipRingMemory.verdict(wa, 2_500))
        assertEquals(Verdict.NONE, VoipRingMemory.verdict(tg, 2_500))
    }

    @Test
    fun `an answer is only good for the call that follows it`() {
        VoipRingMemory.onPosted(wa, "k", true, ongoing = true, groupSummary = false, now = 1_000)
        VoipRingMemory.onPosted(wa, "k", false, ongoing = true, groupSummary = false, now = 2_000)
        assertEquals(Verdict.NONE, VoipRingMemory.verdict(wa, 2_000 + VoipRingMemory.ANSWER_FRESH_MS + VoipRingMemory.UNRESOLVED_MS + 1))
        VoipRingMemory.consume(wa)
        assertEquals(Verdict.NONE, VoipRingMemory.verdict(wa, 2_500))
    }

    @Test
    fun `a stale answer does not mark a later call`() {
        VoipRingMemory.onPosted(wa, "k", true, ongoing = true, groupSummary = false, now = 1_000)
        VoipRingMemory.onPosted(wa, "k", false, ongoing = true, groupSummary = false, now = 2_000)
        // 20 s later a call starts: more than ANSWER_FRESH_MS, so not this answer; the ring is still "heard".
        assertEquals(Verdict.UNRESOLVED_RING, VoipRingMemory.verdict(wa, 2_000 + 2 * VoipRingMemory.ANSWER_FRESH_MS))
    }


    // ---- review C30: a ring that is never closed, or a pickup that is not the call's ----

    @Test
    fun `a ring left active is over after the longest ring time`() {
        VoipRingMemory.onPosted(wa, "0|wa|23", incomingMarker = true, ongoing = false, groupSummary = false, now = 1_000)
        val pastRing = 1_000 + VoipRingMemory.MAX_RING_MS + 1
        // An ongoing call notice of a call placed long after: not this ring's pickup.
        VoipRingMemory.onPosted(wa, "0|wa|99", incomingMarker = false, ongoing = true, groupSummary = false, now = pastRing, callLike = true)
        assertEquals(Verdict.UNRESOLVED_RING, VoipRingMemory.verdict(wa, pastRing + 100))
        assertEquals(Verdict.NONE, VoipRingMemory.verdict(wa, 1_000 + VoipRingMemory.MAX_RING_MS + VoipRingMemory.UNRESOLVED_MS + 1))
    }

    @Test
    fun `a ring rewritten in place into a notice that is neither ringing nor ongoing is over, not answered`() {
        VoipRingMemory.onPosted(tg, "0|tg|203", incomingMarker = true, ongoing = false, groupSummary = false, now = 1_000)
        VoipRingMemory.onPosted(tg, "0|tg|203", incomingMarker = false, ongoing = false, groupSummary = false, now = 5_000)
        VoipRingMemory.onPosted(tg, "0|tg|201", incomingMarker = false, ongoing = true, groupSummary = false, now = 20_000, callLike = true)
        assertEquals(Verdict.UNRESOLVED_RING, VoipRingMemory.verdict(tg, 20_100))
    }

    @Test
    fun `an unrelated ongoing notice of the app while it rings is not the pickup`() {
        VoipRingMemory.onPosted(wa, "0|wa|23", incomingMarker = true, ongoing = true, groupSummary = false, now = 1_000)
        // A backup or upload notice: ongoing, no call markers, another key.
        VoipRingMemory.onPosted(wa, "0|wa|backup", incomingMarker = false, ongoing = true, groupSummary = false, now = 2_000)
        assertEquals(Verdict.UNRESOLVED_RING, VoipRingMemory.verdict(wa, 2_100))
        // The ring's own notification rewritten as ongoing is the pickup.
        VoipRingMemory.onPosted(wa, "0|wa|23", incomingMarker = false, ongoing = true, groupSummary = false, now = 3_000)
        assertEquals(Verdict.ANSWERED_RING, VoipRingMemory.verdict(wa, 3_100))
    }

    @Test
    fun `a call-like ongoing notice of another key is still the pickup while it rings`() {
        VoipRingMemory.onPosted(wa, "0|wa|23", incomingMarker = true, ongoing = false, groupSummary = false, now = 1_000)
        VoipRingMemory.onPosted(wa, "0|wa|24", incomingMarker = false, ongoing = true, groupSummary = false, now = 2_000, callLike = true)
        assertEquals(Verdict.ANSWERED_RING, VoipRingMemory.verdict(wa, 2_100))
    }
}
