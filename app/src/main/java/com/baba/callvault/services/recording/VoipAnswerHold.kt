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
     * Whether "Start when they answer" holds this call's encode: the setting is on, the calling app is known (its
     * notification is what is read), and the call was recognised by its audio mode.
     *
     * Never a normal-mode call (LINE in MODE_NORMAL, BabApps item 108; review C31 rework). LINE's call notice is
     * unproven (Q33): an ongoing notice with `showChronometer=false`, which NotificationCompat writes on every
     * notification, reads RINGING, so the hold would drop the call's audio until an answer that never shows and
     * then delete the call as unanswered. No ring is heard for such a call either, so nothing else could release
     * it. Recording an unanswered LINE call's ringback is the price; losing an answered call is not acceptable.
     */
    fun applies(recordFromAnswer: Boolean, callPackage: String?, viaNormalMode: Boolean): Boolean =
        recordFromAnswer && callPackage != null && !viaNormalMode

    /**
     * Whether a call that ends while its encode is still held is thrown away as unanswered. Only a mode-path call:
     * that is the measured WhatsApp case (Q36). A normal-mode call is kept whatever held it, because nothing about
     * its answer is proven.
     */
    fun discardAsUnanswered(heldForAnswer: Boolean, viaNormalMode: Boolean): Boolean = heldForAnswer && !viaNormalMode

    /**
     * Why a started app call is discarded at its end, or null to keep it. Unanswered is asked FIRST (review N17): a
     * held call's clock stops at the hold, so a 22 s unanswered ring has well under 3 s of audio and no far side,
     * which also reads as [VoipStubPolicy.endedBeforeCapture]. Said that way, the log blamed the capture for
     * a call nobody picked up.
     */
    fun endDiscardReason(unanswered: Boolean, wroteAnything: Boolean, recordedMs: Long?, farHeard: Boolean): VoipOutcome.Reason? = when {
        unanswered -> VoipOutcome.Reason.UNANSWERED
        wroteAnything && VoipStubPolicy.endedBeforeCapture(recordedMs, farHeard) -> VoipOutcome.Reason.ENDED_BEFORE_CAPTURE
        else -> null
    }

    /**
     * What to do on a poll that got [answered] from the host (null = the host has no such method),
     * [elapsedMs] into the hold, given whether an earlier poll already [seenRinging] and whether the ring
     * listener saw the call answered ([ringAnswered]).
     *
     * Once the ringing has been seen, only an answer releases: at hang-up WhatsApp removes its
     * notification before the audio mode drops (measured 2026-09-22), and releasing on that
     * published the last 1.5 s of an unanswered call as a recording. The call's end discards a
     * hold that is still on, so waiting costs nothing.
     */
    fun decide(answered: Int?, elapsedMs: Long, seenRinging: Boolean, ringAnswered: Boolean = false): Decision = when {
        elapsedMs >= MAX_HOLD_MS -> Decision.RELEASE
        answered == RecorderServiceImpl.VOIP_ANSWERED -> Decision.RELEASE
        // The ring listener saw this app's ringing call picked up (VoipRingMemory ANSWERED_RING): an answer read
        // independently of the notification the host reads, which can be the wrong one of the app (review C49).
        ringAnswered -> Decision.RELEASE
        seenRinging -> Decision.HOLD
        answered == RecorderServiceImpl.VOIP_RINGING -> Decision.HOLD
        answered == RecorderServiceImpl.VOIP_NO_NOTIFICATION && elapsedMs < NOTIFICATION_GRACE_MS -> Decision.HOLD
        else -> Decision.RELEASE
    }
}
