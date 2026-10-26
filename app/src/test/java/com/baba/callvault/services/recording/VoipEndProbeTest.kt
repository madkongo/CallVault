/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import com.baba.callvault.services.recording.VoipEndProbe.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The #53 diagnostic: what one line of the end-of-call log says, and when a line is worth writing. */
class VoipEndProbeTest {

    private val inCall = Snapshot(
        mode = AudioManager.MODE_IN_COMMUNICATION,
        voiceCallPlayers = 1, playerKinds = listOf("2:0", "5:4"), voiceCommRecorders = 1,
    )

    @Test
    fun `a line names the mode and counts each signal`() {
        assertEquals(
            "mode=IN_COMMUNICATION vcPlay=1 players=2 [2:0,5:4] vcRec=1",
            VoipEndProbe.describe(inCall),
        )
    }

    @Test
    fun `modes are named, and an unknown one shows its number`() {
        assertEquals("NORMAL", VoipEndProbe.modeName(AudioManager.MODE_NORMAL))
        assertEquals("IN_CALL", VoipEndProbe.modeName(AudioManager.MODE_IN_CALL))
        assertEquals("RINGTONE", VoipEndProbe.modeName(AudioManager.MODE_RINGTONE))
        assertEquals("CALL_SCREENING", VoipEndProbe.modeName(AudioManager.MODE_CALL_SCREENING))
        assertEquals("mode7", VoipEndProbe.modeName(7))
    }

    @Test
    fun `the far side hanging up shows as the voice-call player going idle with the mode still held`() {
        // The Honor case (#53): WhatsApp keeps the mode after hang-up. This is the line we hope to see.
        val afterHangUp = inCall.copy(voiceCallPlayers = 0, playerKinds = emptyList())
        assertTrue(VoipEndProbe.isWorthLogging(previous = inCall, current = afterHangUp))
        assertEquals(
            "mode=IN_COMMUNICATION vcPlay=0 players=0 vcRec=1",
            VoipEndProbe.describe(afterHangUp),
        )
    }

    @Test
    fun `an unchanged snapshot is not logged again, so the log stays small`() {
        assertFalse(VoipEndProbe.isWorthLogging(previous = inCall, current = inCall.copy()))
        assertTrue(VoipEndProbe.isWorthLogging(previous = null, current = inCall))
    }
}
