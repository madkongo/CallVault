/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.server.VoipCallFacts

/**
 * In or out for an app call (BabApps item 113). An app call has no call-log row and no Telecom entry,
 * so the calling app's own notification is the only source, and what it says changes at the pickup.
 * So the rule is: read it ONCE, at the audio-mode flip, keep the first result, and say a direction
 * only on a positive structural signal. Anything else is null and the recording stays unmarked.
 *
 * Structure, never text: titles and texts belong to the app and the locale. In particular Telegram's
 * "Outgoing call" title is on every one of its 1:1 call notifications, incoming or not, and is not a
 * signal.
 */
object VoipDirection {

    /** CallStyle's `CALL_TYPE_INCOMING`. */
    private const val CALL_TYPE_INCOMING = 1

    /**
     * The direction the first read of the call notification proves, or null.
     *
     * - INCOMING: a CallStyle of type incoming, or the app's own incoming flag, or `category=call`
     *   with two or more actions on a notification that is NOT ongoing and has no running timer (the
     *   Answer/Decline pair of a ringing call; an ongoing one with two actions is just Hang up + Mute).
     * - OUTGOING: an ongoing call notification whose timer is not running: the call is dialling or
     *   ringing at the far end, which is only possible for a call this phone placed. Measured on
     *   WhatsApp (OP9, 2026-09-22): "Ringing…" with showChronometer=false.
     * - Otherwise null: already answered at the first read (an incoming call is answered before the
     *   mode flips, but so can a quickly answered outgoing one), an app that sets no timer, or no
     *   notification found.
     */
    fun decide(
        first: VoipCallFacts?,
        ring: VoipRingMemory.Verdict = VoipRingMemory.Verdict.NONE,
        listenerConnected: Boolean = false,
        incomingAlwaysNotified: Boolean = false,
    ): RecordingDirection? {
        // The app rang before the pickup and was answered just now: the only way to prove an incoming
        // call that the app's notification no longer shows (WhatsApp and Telegram both rewrite it).
        if (ring == VoipRingMemory.Verdict.ANSWERED_RING) return RecordingDirection.INCOMING
        if (first != null && isIncomingMarker(first)) return RecordingDirection.INCOMING
        // A ring was seen for this app and not tied to this call: never "outgoing" on top of that.
        if (ring == VoipRingMemory.Verdict.UNRESOLVED_RING) return null
        // An app measured to post its incoming-call notification for EVERY incoming call (Telegram:
        // incoming_calls channel, callType 1, 2 actions; the OP9 probe, 2026-11-17): with the listener
        // connected and no ring heard, the call is one we placed. Its own ongoing notification carries
        // no direction, so this absence is the whole signal.
        if (incomingAlwaysNotified && listenerConnected && ring == VoipRingMemory.Verdict.NONE &&
            first?.showChronometer != true
        ) return RecordingDirection.OUTGOING
        if (first == null || first.showChronometer == true) return null
        // "Ringing, timer not started" is also what an incoming call looks like for the instant between the
        // pickup and the notification's update. Only the ring listener can rule that out: it would have
        // heard the incoming notification. With no listener connected, outgoing is not claimed.
        if (listenerConnected && first.ongoing && first.showChronometer == false) return RecordingDirection.OUTGOING
        return null
    }

    /** The structure of a ringing, incoming call notification: a clear marker, never text. */
    fun isIncomingMarker(f: VoipCallFacts): Boolean =
        f.callType == CALL_TYPE_INCOMING || f.incomingFlag == true ||
            (f.category == "call" && f.actionCount >= 2 && !f.ongoing && f.showChronometer != true)

    /** A direction is only ever set once, and never replaced by a later (weaker) read. */
    fun keepFirst(current: RecordingDirection?, candidate: RecordingDirection?): RecordingDirection? =
        current ?: candidate

    /** `in` / `out`, the tokens a carrier call's file name and the .json details use; null for unknown. */
    fun nameToken(direction: RecordingDirection?): String? = when (direction) {
        RecordingDirection.INCOMING -> "in"
        RecordingDirection.OUTGOING -> "out"
        null -> null
    }
}
