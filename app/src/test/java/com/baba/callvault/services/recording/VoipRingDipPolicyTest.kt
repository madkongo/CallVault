/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 107: a ring-time call-mode dip must not split one outgoing app call in two. */
class VoipRingDipPolicyTest {

    private val burstMs = 4_800L

    @Test
    fun `a 5_9 s dip with no voice player is waited out, so the same recording continues`() {
        // Mode drops 4.8 s into the call; the end would fire only after the wait.
        val delay = VoipRingDipPolicy.endDelayMs(burstMs, voicePlayerEverSeen = false)
        val dipLasts = 5_900L
        assertTrue("the end must not fire before the mode returns", delay > dipLasts)
        // The return is a STAY, which cancels the pending end: one call, one file.
        assertEquals(
            VoipSwitchPolicy.Action.STAY,
            VoipSwitchPolicy.decide(AudioManager.MODE_IN_COMMUNICATION, callActive = true, suspendedForCarrier = false),
        )
    }

    @Test
    fun `a dip after a voice player was seen ends as today`() {
        assertEquals(
            VoipRingDipPolicy.NORMAL_END_DEBOUNCE_MS,
            VoipRingDipPolicy.endDelayMs(burstMs, voicePlayerEverSeen = true),
        )
        assertFalse(VoipRingDipPolicy.isRingDip(burstMs, voicePlayerEverSeen = true))
    }

    @Test
    fun `no return within the wait means the call never connected and is discarded`() {
        val delay = VoipRingDipPolicy.endDelayMs(burstMs, voicePlayerEverSeen = false)
        assertEquals(VoipRingDipPolicy.WAIT_FOR_RETURN_MS, delay)
        assertTrue(VoipRingDipPolicy.neverConnected(endWasDeferred = true, voicePlayerEverSeen = false))
    }

    @Test
    fun `a voice player that appeared during the wait keeps the recording`() {
        assertFalse(VoipRingDipPolicy.neverConnected(endWasDeferred = true, voicePlayerEverSeen = true))
    }

    @Test
    fun `an ordinary end is never a discard`() {
        assertFalse(VoipRingDipPolicy.neverConnected(endWasDeferred = false, voicePlayerEverSeen = false))
    }

    @Test
    fun `the age limit - a call older than the young limit ends as today`() {
        val limit = VoipRingDipPolicy.YOUNG_CALL_MS
        assertTrue(VoipRingDipPolicy.isRingDip(limit - 1, voicePlayerEverSeen = false))
        assertFalse(VoipRingDipPolicy.isRingDip(limit, voicePlayerEverSeen = false))
        assertEquals(
            VoipRingDipPolicy.NORMAL_END_DEBOUNCE_MS,
            VoipRingDipPolicy.endDelayMs(limit + 1_000L, voicePlayerEverSeen = false),
        )
    }

    @Test
    fun `the wait is longer than the measured dips`() {
        // 5.9 s and ~10 s gaps were measured between the burst's end and the answered call.
        assertTrue(VoipRingDipPolicy.WAIT_FOR_RETURN_MS > 10_000L)
    }
}
