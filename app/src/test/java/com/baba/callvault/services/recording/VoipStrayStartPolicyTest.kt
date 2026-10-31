/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import com.baba.callvault.services.recording.VoipStrayStartPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stray 1 s `_voip` file at the end of a Samsung Wi-Fi call (#45, mac's report). */
class VoipStrayStartPolicyTest {

    private val comm = AudioManager.MODE_IN_COMMUNICATION
    private val normal = AudioManager.MODE_NORMAL

    @Test
    fun `the Wi-Fi call hang-up sequence starts nothing`() {
        var latched = false

        // During the call: IN_COMMUNICATION with a managed call. Refused, and the latch is set.
        latched = VoipStrayStartPolicy.latchAfterMode(latched, comm)
        var v = VoipStrayStartPolicy.verdict(latched, managedCall = true)
        assertEquals(Verdict.REFUSE_MANAGED_CALL, v)
        latched = VoipStrayStartPolicy.latchAfterVerdict(latched, v)
        assertTrue(latched)

        // Hang-up: Telecom drops the call, the mode is still IN_COMMUNICATION. This was the stray start.
        latched = VoipStrayStartPolicy.latchAfterMode(latched, comm)
        v = VoipStrayStartPolicy.verdict(latched, managedCall = false)
        assertEquals(Verdict.REFUSE_CARRIER_EPISODE, v)

        // The mode leaves IN_COMMUNICATION: the episode is over.
        latched = VoipStrayStartPolicy.latchAfterMode(latched, normal)
        assertFalse(latched)
    }

    @Test
    fun `a fresh app call after the mode went normal starts`() {
        val latched = VoipStrayStartPolicy.latchAfterMode(true, normal)
        val again = VoipStrayStartPolicy.latchAfterMode(latched, comm)
        assertEquals(Verdict.START, VoipStrayStartPolicy.verdict(again, managedCall = false))
    }

    @Test
    fun `WhatsApp call where OFFHOOK arrives before IN_COMMUNICATION and no managed call starts`() {
        // One UI: OFFHOOK first (our carrier service is starting), then the mode; Telecom has no managed call.
        val latched = VoipStrayStartPolicy.latchAfterMode(false, comm)
        assertEquals(Verdict.START, VoipStrayStartPolicy.verdict(latched, managedCall = false))
    }

    @Test
    fun `the latch stays while the mode stays in communication`() {
        assertTrue(VoipStrayStartPolicy.latchAfterMode(true, comm))
        assertFalse(VoipStrayStartPolicy.latchAfterMode(false, comm))
    }

    @Test
    fun `only a managed-call refusal sets the latch`() {
        assertTrue(VoipStrayStartPolicy.latchAfterVerdict(false, Verdict.REFUSE_MANAGED_CALL))
        assertFalse(VoipStrayStartPolicy.latchAfterVerdict(false, Verdict.START))
        assertTrue(VoipStrayStartPolicy.latchAfterVerdict(true, Verdict.REFUSE_CARRIER_EPISODE))
    }

    @Test
    fun `an ordinary app call with no carrier anywhere starts`() {
        assertEquals(Verdict.START, VoipStrayStartPolicy.verdict(false, managedCall = false))
    }
}
