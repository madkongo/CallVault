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
        absenceMeansOutgoing: Boolean = false,
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
        // no direction, so this absence is the whole signal; [absenceMeansOutgoing] says whether it counts for
        // this recording (C20).
        if (absenceMeansOutgoing && listenerConnected && ring == VoipRingMemory.Verdict.NONE &&
            first?.showChronometer != true
        ) return RecordingDirection.OUTGOING
        if (first == null || first.showChronometer == true) return null
        // "Ringing, timer not started" is also what an incoming call looks like for the instant between the
        // pickup and the notification's update. Only the ring listener can rule that out: it would have
        // heard the incoming notification. With no listener connected, outgoing is not claimed.
        if (listenerConnected && first.ongoing && first.showChronometer == false) return RecordingDirection.OUTGOING
        return null
    }

    /**
     * How long after the call's detection an answer still belongs to it: the audio mode can flip a few ms before
     * the app rewrites its notification as ongoing (measured 32 ms, WhatsApp incoming, 2026-11-17).
     */
    const val RING_ANSWER_WAIT_MS = 3_000L

    /** The longest a call is taken to ring: a listener connected for less than this before the call heard nothing. */
    const val MAX_RING_MS = 90_000L

    /** A recording of the same app that ended this shortly before the call was detected makes it a split (C20). */
    const val SPLIT_WINDOW_MS = 2 * 60_000L

    /**
     * When the ring memory is read for a recording that starts late into its call (an "Ask me" tap): as of the
     * call's detection plus the answer wait, the same read an automatic start makes (review C20). Read at the tap,
     * the memory has forgotten the answer (fresh for 10 s) and the ring (90 s) of an incoming call.
     */
    fun ringEvidenceAt(callDetectedAt: Long): Long = callDetectedAt + RING_ANSWER_WAIT_MS

    /**
     * Whether "no ring heard" may say OUTGOING for this recording (review C20): the app announces every incoming call
     * ([alwaysNotified], Telegram), the listener has been connected since before this call could have rung, and no
     * recording of the same app ended just before (a split call's second file: the first consumed the answer).
     * Anything else leaves the direction unmarked, the documented safe default.
     */
    fun absenceMeansOutgoing(
        alwaysNotified: Boolean,
        listenerConnectedSince: Long,
        callDetectedAt: Long,
        lastRecordingEndedAt: Long,
    ): Boolean = alwaysNotified &&
        listenerConnectedSince > 0L && callDetectedAt - listenerConnectedSince >= MAX_RING_MS &&
        (lastRecordingEndedAt <= 0L || callDetectedAt - lastRecordingEndedAt > SPLIT_WINDOW_MS)

    /**
     * What the ring listener takes for a ringing call (review C30): [isIncomingMarker], but the weak shape (call
     * category with two actions, not ongoing) only with a full-screen intent. A missed-call notice (Call back,
     * Message) has that shape and no full-screen intent; taken for a ring, it made the next outgoing call from the
     * app read as an answered incoming one. A CallStyle of type incoming or the incoming flag need nothing more.
     */
    fun isRingMarker(f: VoipCallFacts, hasFullScreenIntent: Boolean): Boolean =
        f.callType == CALL_TYPE_INCOMING || f.incomingFlag == true || (isIncomingMarker(f) && hasFullScreenIntent)

    /** The structure of a ringing, incoming call notification: a clear marker, never text. */
    fun isIncomingMarker(f: VoipCallFacts): Boolean =
        f.callType == CALL_TYPE_INCOMING || f.incomingFlag == true ||
            (f.category == "call" && f.actionCount >= 2 && !f.ongoing && f.showChronometer != true)

    /**
     * Whether the calling app's notification is read from the recorder again (review C128): only while the first
     * call-like notification has not been found, and only within the grace. The direction is decided at the first
     * read and never changes after it, so a later read only cost a recorder dump every 500 ms for a minute.
     */
    fun readAgain(hasFirstRead: Boolean, elapsedMs: Long): Boolean =
        !hasFirstRead && elapsedMs < VoipAnswerHold.NOTIFICATION_GRACE_MS

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
