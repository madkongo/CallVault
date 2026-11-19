/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.server.RecorderServiceImpl
import kotlinx.coroutines.delay

/**
 * One app call's "Start when they answer" poll loop; the rules per poll are [VoipAnswerHold.decide].
 *
 * Review N17. A poll is a blocking binder call, and cancelling the loop's job does not interrupt it. When the call
 * ended during a poll, the poll came back afterwards ("no notification": WhatsApp removes it at hang-up), the old loop
 * read the call's reset hold flag as "not held yet" and wrote "held until the call is answered" 15 ms after the
 * call's outcome. So every poll is followed by [Host.isLive], and holding, releasing and announcing all go through
 * the host, which refuses once this loop's call is over. "Recording started" is said only when the recording
 * actually captures the call: at the release, or at once when the call is never held.
 */
class VoipAnswerHoldLoop(
    private val host: Host,
    private val normalMode: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** What the loop reads and does; the coordinator in the app, a fake in tests. */
    interface Host {
        /** This loop's call is still the one being recorded. */
        fun isLive(): Boolean
        /** The host's answer for the call's app ([RecorderServiceImpl.VOIP_ANSWERED] and the rest); null = none. */
        fun poll(): Int?
        /** The ring listener saw this call's ringing picked up. */
        fun ringAnswered(): Boolean
        /** Holds or releases the encode. False when this loop's call is over: nothing was changed. */
        fun setHeld(hold: Boolean): Boolean
        /** The recording captures the call from now: "Recording started". False when the call is over: nothing shown. */
        fun recordingFromNow(): Boolean
        /** The always-on log. */
        fun record(line: String)
        /** The debug log. */
        fun debug(line: String)
    }

    suspend fun run() {
        val startedAt = now()
        var polls = 0
        var seenRinging = false
        var held = false
        while (host.isLive()) {
            val answered = host.poll()
            polls++
            if (!host.isLive()) {
                host.debug("app call answer hold: the call ended during poll $polls; nothing more to hold or release")
                return
            }
            val elapsed = now() - startedAt
            if (answered == RecorderServiceImpl.VOIP_RINGING) seenRinging = true
            val ringAnswered = host.ringAnswered()
            if (VoipAnswerHold.decide(answered, elapsed, seenRinging, ringAnswered) == VoipAnswerHold.Decision.RELEASE) {
                release(held, polls, elapsed, why(answered, ringAnswered, elapsed))
                return
            }
            if (!held) {
                if (!host.setHeld(true)) {
                    host.debug("app call answer hold: the call ended before the hold could be armed (poll $polls)")
                    return
                }
                held = true
                host.record("app call answer hold: held until the call is answered (host said ${answerName(answered)}, normal-mode=$normalMode)")
            }
            delay(VoipAnswerHold.POLL_MS)
        }
    }

    private fun release(held: Boolean, polls: Int, elapsed: Long, why: String) {
        if (held && !host.setHeld(false)) {
            host.debug("app call answer hold: the call ended before the release ($why)")
            return
        }
        if (!host.recordingFromNow()) {
            host.debug("app call answer hold: the call ended before the recording could be announced ($why)")
            return
        }
        // Always on (C31): the first LINE calls with the hold are read from these lines.
        host.record(
            if (held) "app call answer hold: released after $polls polls: $why (${elapsed}ms, normal-mode=$normalMode)"
            else "app call answer hold: not held: $why (normal-mode=$normalMode)"
        )
    }

    private fun why(answered: Int?, ringAnswered: Boolean, elapsed: Long): String = when {
        answered == RecorderServiceImpl.VOIP_ANSWERED -> "call answered"
        ringAnswered -> "the ring listener saw the call answered"
        elapsed >= VoipAnswerHold.MAX_HOLD_MS -> "held ${elapsed / 1000}s without an answer"
        answered == null -> "the host has no answer to give (older daemon?)"
        answered == RecorderServiceImpl.VOIP_NO_TIMER -> "this app's notification shows no call timer"
        else -> "no call notification appeared within ${elapsed / 1000}s"
    }

    private fun answerName(answered: Int?): String = when (answered) {
        RecorderServiceImpl.VOIP_ANSWERED -> "answered"
        RecorderServiceImpl.VOIP_RINGING -> "ringing"
        RecorderServiceImpl.VOIP_NO_TIMER -> "no timer"
        RecorderServiceImpl.VOIP_NO_NOTIFICATION -> "no notification"
        null -> "nothing (older host)"
        else -> "code $answered"
    }
}
