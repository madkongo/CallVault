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

    @Test
    fun `a LINE call recognised in normal mode is never held, its answer signal is unproven`() {
        // Review C31 rework: LINE's call notice shape is unknown (Q33). An ongoing notice with
        // showChronometer=false (NotificationCompat writes it on every notification) reads RINGING, and the
        // hold would drop the call's audio and delete it at the end as unanswered.
        assertEquals(false, VoipAnswerHold.applies(recordFromAnswer = true, callPackage = "jp.naver.line.android", viaNormalMode = true))
        assertEquals(true, VoipAnswerHold.applies(recordFromAnswer = true, callPackage = "com.whatsapp", viaNormalMode = false))
    }

    @Test
    fun `no hold when the setting is off or the app is unknown`() {
        assertEquals(false, VoipAnswerHold.applies(recordFromAnswer = false, callPackage = "com.whatsapp", viaNormalMode = false))
        assertEquals(false, VoipAnswerHold.applies(recordFromAnswer = true, callPackage = null, viaNormalMode = false))
    }

    @Test
    fun `a LINE call whose notice reads ringing and that ends while held is kept, not deleted`() {
        // The exact failure: an ongoing LINE-like notice marked as a call with the timer flag false.
        val lineNotice = """
            NotificationRecord(0x01: pkg=jp.naver.line.android user=UserHandle{0} id=7 tag=null
              Notification(channel=call flags=ONGOING_EVENT|NO_CLEAR category=call actions=2)
              extras={
                    android.title=String (LINE)
                    android.showChronometer=Boolean (false)
              }
        """.trimIndent()
        val read = com.baba.callvault.server.VoipCallerName.extractAnswerState(lineNotice, "jp.naver.line.android")
        assertEquals(com.baba.callvault.server.VoipCallerName.AnswerState.RINGING, read)
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_RINGING, elapsedMs = 20_000, seenRinging = true))
        // Whatever held it, a normal-mode call that ends held is kept.
        assertEquals(false, VoipAnswerHold.discardAsUnanswered(heldForAnswer = true, viaNormalMode = true))
        // A mode-path call (WhatsApp, measured Q36) still leaves no file when nobody picked up.
        assertEquals(true, VoipAnswerHold.discardAsUnanswered(heldForAnswer = true, viaNormalMode = false))
        assertEquals(false, VoipAnswerHold.discardAsUnanswered(heldForAnswer = false, viaNormalMode = false))
    }

    @Test
    fun `an answer the ring listener saw releases a hold the notification read keeps on`() {
        // Review C49: once RINGING was read (possibly from the wrong notification of the app), only ANSWERED or
        // two minutes released, and an answered call that ended inside the hold was deleted as unanswered. The
        // ring listener's own pickup (VoipRingMemory ANSWERED_RING) is an independent answer.
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_RINGING, elapsedMs = 5_000, seenRinging = true, ringAnswered = true))
        assertEquals(Decision.RELEASE, VoipAnswerHold.decide(VOIP_NO_NOTIFICATION, elapsedMs = 0, seenRinging = false, ringAnswered = true))
        assertEquals(Decision.HOLD, VoipAnswerHold.decide(VOIP_RINGING, elapsedMs = 5_000, seenRinging = true, ringAnswered = false))
    }
}
