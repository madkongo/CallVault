/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.server.RecorderServiceImpl
import com.baba.callvault.services.recording.VoipAnswerHold.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Start when they answer" on an app call: the capture must start at the audio-mode flip (arming
 * cannot be retried), so the encode is held paused and released at the pickup. These are the rules
 * for each poll of the host's `voipCallAnswered`.
 */
class VoipAnswerHoldTest {

    @Test
    fun `a ringing call is held`() {
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(RecorderServiceImpl.VOIP_RINGING, elapsedMs = 0))
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(RecorderServiceImpl.VOIP_RINGING, elapsedMs = 30_000))
    }

    @Test
    fun `an answered call is released`() {
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(RecorderServiceImpl.VOIP_ANSWERED, elapsedMs = 9_000))
    }

    @Test
    fun `an app that does not say is released at once`() {
        // No timer on the notification, no notification yet, or an older host without the method:
        // the option is simply off for this call. Never a two-minute wait for a signal that will not come.
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(RecorderServiceImpl.VOIP_ANSWER_UNKNOWN, elapsedMs = 0))
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(null, elapsedMs = 0))
    }

    @Test
    fun `the hold has the same ceiling as the phone-call wait`() {
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(RecorderServiceImpl.VOIP_RINGING, elapsedMs = VoipAnswerHold.MAX_HOLD_MS - 1))
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(RecorderServiceImpl.VOIP_RINGING, elapsedMs = VoipAnswerHold.MAX_HOLD_MS))
    }
}
