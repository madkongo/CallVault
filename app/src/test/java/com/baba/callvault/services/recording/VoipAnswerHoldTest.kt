/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_ANSWERED
import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_NO_NOTIFICATION
import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_NO_TIMER
import com.baba.callvault.server.RecorderServiceImpl.Companion.VOIP_RINGING
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
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_RINGING, elapsedMs = 0, seenRinging = false))
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_RINGING, elapsedMs = 30_000, seenRinging = true))
    }

    @Test
    fun `an answered call is released`() {
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_ANSWERED, elapsedMs = 9_000, seenRinging = true))
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_ANSWERED, elapsedMs = 0, seenRinging = false))
    }

    @Test
    fun `an app whose notification has no timer is released at once`() {
        // This app never says. Never a two-minute wait for a signal that will not come.
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_NO_TIMER, elapsedMs = 0, seenRinging = false))
    }

    @Test
    fun `an older host without the method is released at once`() {
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(null, elapsedMs = 0, seenRinging = false))
    }

    @Test
    fun `a notification not posted yet is waited for briefly`() {
        // Call notifications follow the audio by a moment (the late-caller retries exist for the
        // same reason). A short grace, then it is treated as an app that never says.
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_NO_NOTIFICATION, elapsedMs = 0, seenRinging = false))
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_NO_NOTIFICATION, elapsedMs = VoipAnswerHold.NOTIFICATION_GRACE_MS - 1, seenRinging = false))
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_NO_NOTIFICATION, elapsedMs = VoipAnswerHold.NOTIFICATION_GRACE_MS, seenRinging = false))
    }

    @Test
    fun `once ringing was seen, only an answer releases`() {
        // Measured 2026-09-22 on the OP9: at hang-up WhatsApp removes its notification BEFORE the
        // audio mode drops. Releasing on that published 1.5 s of nothing as a recording. A hold that
        // has seen the ringing waits for the answer or the end of the call, whichever comes.
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_NO_NOTIFICATION, elapsedMs = 13_000, seenRinging = true))
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_NO_TIMER, elapsedMs = 13_000, seenRinging = true))
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(null, elapsedMs = 13_000, seenRinging = true))
    }

    @Test
    fun `the hold has the same ceiling as the phone-call wait`() {
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_RINGING, elapsedMs = VoipAnswerHold.MAX_HOLD_MS - 1, seenRinging = true))
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_RINGING, elapsedMs = VoipAnswerHold.MAX_HOLD_MS, seenRinging = true))
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_NO_NOTIFICATION, elapsedMs = VoipAnswerHold.MAX_HOLD_MS, seenRinging = true))
    }
}
