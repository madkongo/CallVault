/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.VoipManualStartPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Ask me" prompt's Record tap ([VoipCallDetector.startNow]). */
class VoipManualStartPolicyTest {

    @Test
    fun `a LINE call recorded from the prompt starts as a normal-mode call`() {
        // Review C26: the tap used to start every call as a mode call, so a LINE call (item 108) lost its
        // normal-mode handling and its capture-health line.
        assertEquals(
            Decision.Start(viaNormalMode = true),
            VoipManualStartPolicy.decide(callActive = true, isRecording = false, normalModeCall = true, carrierCallUp = false),
        )
    }

    @Test
    fun `a mode-path call recorded from the prompt starts as a mode call`() {
        assertEquals(
            Decision.Start(viaNormalMode = false),
            VoipManualStartPolicy.decide(callActive = true, isRecording = false, normalModeCall = false, carrierCallUp = false),
        )
    }

    @Test
    fun `no call up or already recording is ignored`() {
        assertEquals(Decision.Ignore, VoipManualStartPolicy.decide(callActive = false, isRecording = false, normalModeCall = false, carrierCallUp = false))
        assertEquals(Decision.Ignore, VoipManualStartPolicy.decide(callActive = true, isRecording = true, normalModeCall = true, carrierCallUp = false))
    }

    @Test
    fun `a Record tap during a phone call starts nothing`() {
        // Review C16: the prompt of a LINE call stays up through a phone call; a tap then opened the app-call
        // capture against the carrier recording (Q39: a second voice capture drops the user's own side).
        assertEquals(
            Decision.RefuseCarrierCall,
            VoipManualStartPolicy.decide(callActive = true, isRecording = false, normalModeCall = true, carrierCallUp = true),
        )
        assertEquals(
            Decision.RefuseCarrierCall,
            VoipManualStartPolicy.decide(callActive = true, isRecording = false, normalModeCall = false, carrierCallUp = true),
        )
    }
}
