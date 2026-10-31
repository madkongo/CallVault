/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import com.baba.callvault.services.recording.VoipStrayStartPolicy.Latch
import com.baba.callvault.services.recording.VoipStrayStartPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stray 1 s `_voip` file at the end of a Samsung Wi-Fi call (#45, mac's report). */
class VoipStrayStartPolicyTest {

    private val comm = AudioManager.MODE_IN_COMMUNICATION
    private val normal = AudioManager.MODE_NORMAL
    private val tail = VoipStrayStartPolicy.TAIL_MS

    /** What the detector does on a START it has to judge, at [now]. */
    private fun judge(latch: Latch, managed: Boolean, now: Long): Pair<Latch, Verdict> {
        val timed = VoipStrayStartPolicy.afterTime(latch, managed, now)
        val v = VoipStrayStartPolicy.verdict(timed.active, managed)
        return VoipStrayStartPolicy.afterVerdict(timed, v) to v
    }

    @Test
    fun `the Wi-Fi call hang-up sequence starts nothing`() {
        // During the call: IN_COMMUNICATION with a managed call. Refused, and the latch is set.
        var (latch, v) = judge(Latch(), managed = true, now = 0)
        assertEquals(Verdict.REFUSE_MANAGED_CALL, v)
        assertTrue(latch.active)

        // Hang-up: Telecom drops the call, the mode stays IN_COMMUNICATION ~1.5 s. This was the stray start.
        latch = VoipStrayStartPolicy.afterMode(latch, comm)
        judge(latch, managed = false, now = 60_000).let { (l, verdict) -> latch = l; v = verdict }
        assertEquals(Verdict.REFUSE_CARRIER_EPISODE, v)
        judge(latch, managed = false, now = 61_500).let { (l, verdict) -> latch = l; v = verdict }
        assertEquals(Verdict.REFUSE_CARRIER_EPISODE, v)

        // The mode leaves IN_COMMUNICATION: the episode is over.
        latch = VoipStrayStartPolicy.afterMode(latch, normal)
        assertFalse(latch.active)
    }

    @Test
    fun `a fresh app call after the mode went normal starts`() {
        val latch = VoipStrayStartPolicy.afterMode(Latch(active = true), normal)
        val again = VoipStrayStartPolicy.afterMode(latch, comm)
        assertEquals(Verdict.START, judge(again, managed = false, now = 0).second)
    }

    @Test
    fun `WhatsApp call where OFFHOOK arrives before IN_COMMUNICATION and no managed call starts`() {
        // One UI: OFFHOOK first (our carrier service is starting), then the mode; Telecom has no managed call.
        val latch = VoipStrayStartPolicy.afterMode(Latch(), comm)
        assertEquals(Verdict.START, judge(latch, managed = false, now = 0).second)
    }

    @Test
    fun `a WhatsApp call that carries on after the phone call ended is recorded after the tail`() {
        // Phone call up, user swaps to WhatsApp: refused while the managed call exists.
        var (latch, v) = judge(Latch(), managed = true, now = 0)
        assertEquals(Verdict.REFUSE_MANAGED_CALL, v)
        // The phone call hangs up, WhatsApp keeps the mode: refused inside the tail...
        judge(latch, managed = false, now = 10_000).let { (l, verdict) -> latch = l; v = verdict }
        assertEquals(Verdict.REFUSE_CARRIER_EPISODE, v)
        assertEquals(tail, VoipStrayStartPolicy.tailRemainingMs(latch, 10_000))
        // ...and allowed once it has passed, though the mode never left IN_COMMUNICATION.
        judge(latch, managed = false, now = 10_000 + tail).let { (l, verdict) -> latch = l; v = verdict }
        assertEquals(Verdict.START, v)
        assertFalse(latch.active)
    }

    @Test
    fun `the managed call coming back restarts the tail`() {
        val inTail = Latch(active = true, managedEndedAt = 1_000)
        val back = VoipStrayStartPolicy.afterTime(inTail, managedCall = true, nowMs = 2_000)
        assertEquals(Latch(active = true), back)
        assertNull(VoipStrayStartPolicy.tailRemainingMs(back, 2_000))
    }

    @Test
    fun `the latch stays while the mode stays in communication`() {
        assertTrue(VoipStrayStartPolicy.afterMode(Latch(active = true), comm).active)
        assertFalse(VoipStrayStartPolicy.afterMode(Latch(), comm).active)
    }

    @Test
    fun `only a managed-call refusal sets the latch`() {
        assertTrue(VoipStrayStartPolicy.afterVerdict(Latch(), Verdict.REFUSE_MANAGED_CALL).active)
        assertFalse(VoipStrayStartPolicy.afterVerdict(Latch(), Verdict.START).active)
        assertTrue(VoipStrayStartPolicy.afterVerdict(Latch(active = true), Verdict.REFUSE_CARRIER_EPISODE).active)
    }

    @Test
    fun `an ordinary app call with no carrier anywhere starts`() {
        assertEquals(Verdict.START, judge(Latch(), managed = false, now = 0).second)
    }
}
