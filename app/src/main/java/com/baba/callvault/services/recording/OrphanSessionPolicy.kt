/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

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

    /**
     * How much earlier than the live call's beginning an orphan's recording must have started before it is taken
     * for an earlier call's (review C2 second pass). A recording of the live call starts at its off-hook, which is
     * at or after the ring or dial that began it, and both times are written by the same process; the margin only
     * absorbs a wall-clock adjustment between the two. The cost of a too-small margin is an earlier call shorter
     * than it, ended right before this one rang, being kept and adopted; of a too-large one, nothing (a same-call
     * recording never starts before its call began).
     */
    const val SAME_CALL_MARGIN_MS = 2_000L

    /**
     * A call logged more than this after the orphan's recording began is another call, not the orphan's own. Short on
     * purpose: an incoming call is logged at its ring (before the recording) and an outgoing one at its dial (about the
     * recording's start), so only clock slack is needed, and a quick redial within a minute must still be seen.
     */
    /** A file that runs on past its call's end by more than this is told to the user (a hang-up is never exact). */
    const val RUN_ON_NOTICE_MS = 30_000L

    /**
     * Seconds the recording ran on after its call ended, when that is worth saying (more than [RUN_ON_NOTICE_MS]), else
     * null. [callEndedAt] counts only when it is not older than the orphan's start (it may be an earlier call's end).
     */
    fun ranOnSeconds(stoppedAt: Long, callEndedAt: Long?, orphanStartedAt: Long?): Int? {
        if (callEndedAt == null) return null
        if (orphanStartedAt != null && callEndedAt < orphanStartedAt) return null
        val gap = stoppedAt - callEndedAt
        return if (gap > RUN_ON_NOTICE_MS) (gap / 1_000L).toInt() else null
    }

    const val SAME_CALL_LOG_SLACK_MS = 10_000L

    /** True when the orphan's recording began clearly before the live call did, so it cannot be that call's. */
    fun orphanPredatesLiveCall(orphanStartedAt: Long?, liveCallBeganAt: Long?): Boolean =
        orphanStartedAt != null && liveCallBeganAt != null && orphanStartedAt < liveCallBeganAt - SAME_CALL_MARGIN_MS

    /**
     * Whether the call the orphaned recording started on is known to be over, while some call is in progress.
     *
     * @param orphanStartedAt when the orphan's recording started (its staging note); null when unknown.
     * @param lastNoCallAt the last moment CallVault saw no call at all (no phone call and no call audio mode), 0 if never.
     * @param liveCallBeganAt when the call in progress began ringing or was dialled, as seen by CallVault (kept across
     *   a process kill); null when CallVault did not see it begin. A restart in the middle of a call (Q25) keeps the
     *   value the killed process wrote, so the live call's own recording is never older than it.
     *
     * The call log is deliberately not used: it matches by time alone and took an earlier finished call for the live
     * one, which cut a live recording at a OnePlus restart mid-call.
     */
    fun orphanCallEnded(orphanStartedAt: Long?, lastNoCallAt: Long, liveCallBeganAt: Long?): Boolean {
        if (orphanStartedAt == null) return false
        if (lastNoCallAt > orphanStartedAt) return true
        return orphanPredatesLiveCall(orphanStartedAt, liveCallBeganAt)
    }

    /**
     * When the orphan's recording started (review C2 second pass).
     *
     * @param sessionFileStart the note of the staged file the app handed to the recorder for its session, recorded at
     *   the start and kept across a process kill: the file the recorder is actually writing. Preferred, always.
     * @param candidateStarts the notes of the other staged files, used only when that record is missing (a session
     *   started by a build before this one).
     * @param lastNoSessionAt the last time the app knew the recorder was not recording. A note from before it cannot
     *   be the running session's, so a leftover staged file that the rescue has not published yet is never taken.
     */
    fun orphanStartFrom(sessionFileStart: Long?, candidateStarts: List<Long>, lastNoSessionAt: Long): Long? =
        sessionFileStart ?: candidateStarts.filter { it > lastNoSessionAt }.maxOrNull()

    /** Whether ending the session on [endingPath] removes the record [recordedPath]: only when it is that same file. */
    fun endsRecordedSession(recordedPath: String?, endingPath: String?): Boolean =
        recordedPath != null && recordedPath == endingPath

    /**
     * Whether a phone call's ring or dial may be noted as "the call in progress began" (gate review item 1).
     *
     * Not while an app call is being recorded or its audio mode is up: a ring that is then rejected during a live
     * WhatsApp call (Q28) would move the beginning past the app call's own recording, and a later kill mid-call
     * (Q25) would then stop that live recording. A phone call ANSWERED during an app call is noted separately, when
     * the app-call recording is ended for it (C1): from then on the phone call is the live call.
     */
    fun mayNoteCarrierCallBegan(
        carrierSessionActive: Boolean,
        ringingOrFreshDial: Boolean,
        appCallRecording: Boolean,
        appCallModeUp: Boolean,
    ): Boolean = !carrierSessionActive && ringingOrFreshDial && !appCallRecording && !appCallModeUp

    /**
     * Whether any call is ringing or up (gate review item 2). Besides telephony, Telecom and the call audio modes:
     * an app call this process is recording, and a live voice-communication player or recorder. A LINE call (Q33)
     * never leaves MODE_NORMAL, and after a restart only its players and recorders show it. The recorder's own
     * capture is never one of them: it records from MIC and VOICE_CALL, and plays nothing.
     * Every input that could not be read is null; any null with nothing else true reads as "in progress".
     */
    fun isCallInProgress(
        carrierState: Int?,
        audioModeIsCall: Boolean?,
        telecomInCall: Boolean?,
        appCallRecording: Boolean,
        voiceCommPlayers: Int?,
        voiceCommRecorders: Int?,
    ): Boolean {
        val known = listOf(
            carrierState?.let { it != 0 /* TelephonyManager.CALL_STATE_IDLE */ },
            audioModeIsCall, telecomInCall, appCallRecording,
            voiceCommPlayers?.let { it > 0 }, voiceCommRecorders?.let { it > 0 },
        )
        if (known.any { it == true }) return true
        return known.any { it == null }
    }

    /** What a call start should do once the orphan question is settled. */
    enum class StartVerdict {
        /** Nothing in the way, or the orphan was stopped: start this call's recording. */
        START,

        /** The recorder is busy and would refuse: say so, never report a start. */
        FAIL_BUSY,

        /** The orphan is this call's recording (Q25): do not start a second one, and do not call it a failure. */
        ADOPT,

        /** Another owner in this process is stopping its session (an app call ended for a phone call, C1): wait for it. */
        WAIT_FOR_OWNER,

        /** The busy session is this same start's own capture, still running for this call: keep it, never error out. */
        KEEP_OWN,
    }

    /**
     * @param stillRecording what the recorder said after a stop was asked, null when no stop was asked.
     * @param ownedBySelf the session belongs to the very start asking (its own earlier attempt for this call).
     * @param otherOwnerReleasing every other owner in this process has begun stopping its session.
     */
    fun forStart(
        action: Action,
        stillRecording: Boolean?,
        ownedBySelf: Boolean = false,
        otherOwnerReleasing: Boolean = false,
    ): StartVerdict = when (action) {
        Action.NONE -> StartVerdict.START
        Action.OWNED -> when {
            ownedBySelf -> StartVerdict.KEEP_OWN
            otherOwnerReleasing -> StartVerdict.WAIT_FOR_OWNER
            else -> StartVerdict.FAIL_BUSY
        }
        Action.KEEP_LIVE_CALL -> StartVerdict.ADOPT
        Action.STOP_ORPHAN -> if (stillRecording == false) StartVerdict.START else StartVerdict.FAIL_BUSY
    }
}
