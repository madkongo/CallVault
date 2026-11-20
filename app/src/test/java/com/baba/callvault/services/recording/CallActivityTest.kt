/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review C54: the stuck-mic heal's in-call gate must see a ringing app call, not only an answered one. */
class CallActivityTest {

    private fun inputs(
        notice: Boolean = false, voip: Boolean = false, mode: Boolean = false, managed: Boolean = false,
        telecom: Boolean = false, telephony: Boolean = false, ring: Boolean = false,
    ) = CallActivity.Inputs(notice, voip, mode, managed, telecom, telephony, ring)

    @After
    fun clear() = VoipRingMemory.clearForTest()

    @Test
    fun `nothing going on is not a call`() {
        assertFalse(CallActivity.isActive(inputs()))
    }

    @Test
    fun `each signal alone counts as a call`() {
        assertTrue(CallActivity.isActive(inputs(notice = true)))
        assertTrue(CallActivity.isActive(inputs(voip = true)))
        assertTrue(CallActivity.isActive(inputs(mode = true)))
        assertTrue(CallActivity.isActive(inputs(managed = true)))
        assertTrue(CallActivity.isActive(inputs(telecom = true)))
        assertTrue(CallActivity.isActive(inputs(telephony = true)))
        assertTrue(CallActivity.isActive(inputs(ring = true)))
    }

    @Test
    fun `an app call that is still ringing, with the audio mode still normal, counts`() {
        VoipRingMemory.onPosted("com.whatsapp", "k1", incomingMarker = true, ongoing = false, groupSummary = false, now = 1_000L)
        assertTrue(VoipRingMemory.anyRingActive(now = 2_000L))
    }

    @Test
    fun `a ring that was declined no longer counts`() {
        VoipRingMemory.onPosted("com.whatsapp", "k1", incomingMarker = true, ongoing = false, groupSummary = false, now = 1_000L)
        VoipRingMemory.onRemoved("com.whatsapp", "k1", now = 5_000L)
        assertFalse(VoipRingMemory.anyRingActive(now = 6_000L))
    }

    @Test
    fun `a ring whose notice was never removed stops counting after the ring limit`() {
        VoipRingMemory.onPosted("com.whatsapp", "k1", incomingMarker = true, ongoing = false, groupSummary = false, now = 1_000L)
        assertFalse(VoipRingMemory.anyRingActive(now = 1_000L + VoipRingMemory.MAX_RING_MS + 1))
    }

    @Test
    fun `a call just answered counts until the recording has had time to start`() {
        VoipRingMemory.onPosted("com.whatsapp", "k1", incomingMarker = true, ongoing = false, groupSummary = false, now = 1_000L)
        VoipRingMemory.onPosted("com.whatsapp", "k1", incomingMarker = false, ongoing = true, groupSummary = false, now = 3_000L, callLike = true)
        assertTrue(VoipRingMemory.anyRingActive(now = 4_000L))
        assertFalse(VoipRingMemory.anyRingActive(now = 3_000L + VoipRingMemory.ANSWER_FRESH_MS + 1))
    }
}
