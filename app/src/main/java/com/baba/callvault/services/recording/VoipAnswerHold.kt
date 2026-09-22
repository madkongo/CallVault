/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.server.RecorderServiceImpl
import com.baba.callvault.services.call.AnswerWait

/**
 * "Start when they answer" for an app call.
 *
 * The phone-call version delays the start; this one cannot, because the VoIP capture has to open at
 * the audio-mode flip or not at all (see [VoipRecordingCoordinator]). So the capture is started and
 * held **paused** — frames dropped, the microphone untouched — while the host is polled for the call
 * timer on the app's notification (`voipCallAnswered`), and released at the pickup. Measured on
 * WhatsApp; an app that shows no timer never says, and is released at once.
 */
object VoipAnswerHold {

    /** How often the host is asked; the same cadence as [AnswerWait]. */
    const val POLL_MS = AnswerWait.POLL_MS

    /** The same ceiling as the phone-call wait, for the same reason: a hold must never eat a call. */
    const val MAX_HOLD_MS = AnswerWait.MAX_WAIT_MS

    /**
     * How long a notification that is not there yet is waited for. Call notifications follow the
     * audio by a moment — the late-caller retries exist for the same reason — and a hold that gave
     * up on the first poll would miss most calls it was meant to help.
     */
    const val NOTIFICATION_GRACE_MS = 5_000L

    enum class Decision { HOLD, RELEASE }

    /**
     * What to do on a poll that got [answered] from the host (null = the host has no such method),
     * [elapsedMs] into the hold, given whether an earlier poll already [seenRinging].
     *
     * Once the ringing has been seen, only an answer releases: at hang-up WhatsApp removes its
     * notification before the audio mode drops (measured 2026-09-22), and releasing on that
     * published the last 1.5 s of an unanswered call as a recording. The call's end discards a
     * hold that is still on, so waiting costs nothing.
     */
    fun decide(answered: Int?, elapsedMs: Long, seenRinging: Boolean): Decision = when {
        elapsedMs >= MAX_HOLD_MS -> Decision.RELEASE
        answered == RecorderServiceImpl.VOIP_ANSWERED -> Decision.RELEASE
        seenRinging -> Decision.HOLD
        answered == RecorderServiceImpl.VOIP_RINGING -> Decision.HOLD
        answered == RecorderServiceImpl.VOIP_NO_NOTIFICATION && elapsedMs < NOTIFICATION_GRACE_MS -> Decision.HOLD
        else -> Decision.RELEASE
    }
}
