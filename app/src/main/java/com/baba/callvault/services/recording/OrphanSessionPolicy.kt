/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.health.CallLogEntry

/**
 * What to do with a recorder session no process owns any more (review C2, N4).
 *
 * Proven on the OP9 (Shizuku) 2026-11-19: the app was killed 8 s into a call; the recorder kept recording
 * past the hang-up and nothing stopped it. The next call's start was refused ("already recording") while
 * the app said it had started, so both calls went into the first call's file and the rescue published it
 * as one recording under the first call's name.
 *
 * The other side of it is quirk Q25: a OnePlus kills and restarts CallVault MID-call. The session the
 * restarted app finds is then the live call's own recording. Stopping it would cut that call in two, so a
 * session is only stopped when its call is known to be over: no call is in progress at all, or a call-free
 * moment / a finished call-log entry shows that the call it started on has ended.
 */
object OrphanSessionPolicy {

    enum class Action {
        /** The recorder is not recording. Nothing to do. */
        NONE,

        /** This process owns the session. Not an orphan. */
        OWNED,

        /** An orphan whose call is over: stop it so its file is finalised and the rescue publishes it. */
        STOP_ORPHAN,

        /** An orphan that may be recording the call still in progress (Q25): leave it, it is that call's recording. */
        KEEP_LIVE_CALL,
    }

    /**
     * @param daemonRecording what the recorder says about itself.
     * @param ownedHere a session this process started and has not released ([com.baba.callvault.server.DaemonSessionOwnership]).
     * @param callInProgress any call (phone or app) is ringing or up right now; an unreadable state counts as true.
     * @param orphanCallEnded the call the orphan started on is known to have ended ([orphanCallEnded]).
     */
    fun decide(daemonRecording: Boolean, ownedHere: Boolean, callInProgress: Boolean, orphanCallEnded: Boolean): Action = when {
        !daemonRecording -> Action.NONE
        ownedHere -> Action.OWNED
        // No call at all: whatever the recorder is recording, its call is over.
        !callInProgress -> Action.STOP_ORPHAN
        // A call is up, but it is not the orphan's: the orphan's call has ended (the N4 case, the next call).
        orphanCallEnded -> Action.STOP_ORPHAN
        // A call is up and nothing shows the orphan's call ended: it may be this very call (Q25). Never cut it.
        else -> Action.KEEP_LIVE_CALL
    }

    /** How long before the recording started its call may have started (ringing, dialling). */
    const val MAX_RING_BEFORE_RECORDING_MS = 5 * 60_000L

    /** How long after the recording started its call-log entry may say it started (clocks, late start). */
    const val CALL_START_SLACK_MS = 60_000L

    /**
     * Whether the call the orphaned recording started on is known to be over.
     *
     * @param orphanStartedAt when the orphan's recording started (its staging note); null when unknown.
     * @param lastNoCallAt the last moment CallVault saw no call at all (a phone IDLE, an app call's end), 0 if never.
     * @param callLog finished phone calls. The system writes an entry only when a call ends, so an entry for
     *   the orphan's call proves that call is over. An app call has none; then only [lastNoCallAt] can tell.
     */
    fun orphanCallEnded(orphanStartedAt: Long?, lastNoCallAt: Long, callLog: List<CallLogEntry>): Boolean {
        if (orphanStartedAt == null) return false
        if (lastNoCallAt > orphanStartedAt) return true
        return callLog.any { it.startedAt in (orphanStartedAt - MAX_RING_BEFORE_RECORDING_MS)..(orphanStartedAt + CALL_START_SLACK_MS) }
    }

    /** What a call start should do once the orphan question is settled. */
    enum class StartVerdict {
        /** Nothing in the way, or the orphan was stopped: start this call's recording. */
        START,

        /** The recorder is busy and would refuse: say so, never report a start. */
        FAIL_BUSY,

        /** The orphan is this call's recording (Q25): do not start a second one, and do not call it a failure. */
        ADOPT,
    }

    /**
     * @param stillRecording what the recorder said after a stop was asked, null when no stop was asked.
     */
    fun forStart(action: Action, stillRecording: Boolean?): StartVerdict = when (action) {
        Action.NONE -> StartVerdict.START
        Action.OWNED -> StartVerdict.FAIL_BUSY
        Action.KEEP_LIVE_CALL -> StartVerdict.ADOPT
        Action.STOP_ORPHAN -> if (stillRecording == false) StartVerdict.START else StartVerdict.FAIL_BUSY
    }
}
