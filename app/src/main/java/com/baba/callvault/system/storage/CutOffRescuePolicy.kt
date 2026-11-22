/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.data.health.CallLogEntry

/** What to do with one staged recording a finished call did not publish. */
enum class RescueDecision {
    /** A call is up or the file changed recently: it may still be being written. Look again later. */
    WAIT,

    /** Publish it under the name its note gives. */
    SAVE_NAMED,

    /**
     * A non-empty staged file with NO note beside it: we cannot tell a genuine cut-off from a
     * normally-finished call whose temp was merely orphaned. Left in place — never published, never
     * reported. See [decide].
     */
    LEAVE_UNATTRIBUTED,

    /** Zero bytes: there is no audio to save, only a file to remove. */
    DISCARD_EMPTY,
}

/**
 * The rules for saving a recording cut off by CallVault being killed (backlog #4, a tester 2026-09-28).
 *
 * Three promises: a recording still being made is never touched; nothing holding audio is ever deleted;
 * and a call is only reported "cut off" when we can actually attribute the file to an interrupted
 * recording — i.e. it has a [StagingNote]. A staged temp with no note is UNCLASSIFIABLE: it is equally a
 * cut-off from an old build OR a normally-published call whose temp lingered. The first release guessed
 * "cut off" for these and cried wolf on a pre-2.4.4 leftover (OP12, 2026-09-29, `recovered_20260928_…`),
 * so a no-note file is now left alone. The cost is that a genuine cut-off from before notes existed is
 * not auto-recovered; that is far better than a false "a call was cut off" on a call that recorded fine.
 */
object CutOffRescuePolicy {
    /**
     * How long a staged file must have been untouched before it counts as abandoned. A live recording is
     * written every few hundred milliseconds, so 90 s of silence cannot be one that is still running.
     */
    const val QUIET_MS = 90_000L

    /** A file that has waited with a call up for this long, continuously, is a stuck state, not a long call. */
    const val BUSY_BACKOFF_AFTER_MS = 60 * 60_000L

    /** The ordinary request's delay once backed off (battery); a call end returns it to normal. */
    const val BUSY_BACKOFF_DELAY_MS = 30 * 60_000L

    /** Whether the continuous "call up" wait that began at [busySince] (0 = not waiting) has lasted into back-off. */
    fun shouldBackOff(busySince: Long, now: Long): Boolean = busySince > 0L && now - busySince >= BUSY_BACKOFF_AFTER_MS

    /** How many 15 s re-checks a marked file gets before the ordinary quiet-wait request takes over. */
    const val CONFIRMED_MAX_RECHECKS = 8

    /** What a marked file that still waits does next: look again soon, or hand over to the ordinary quiet-wait request. */
    fun handOverToQuietWait(attempt: Int): Boolean = attempt + 1 >= CONFIRMED_MAX_RECHECKS

    /** The quiet a file the recorder confirmed stopped must still show: more than twice the recorder's 6 s stop wait. */
    const val CONFIRMED_QUIET_MS = 15_000L

    /**
     * @param ownedByLiveRecording a recording in this process still owns the file ([LiveStagingFiles]): a paused
     *   recording is quiet for as long as the pause lasts, and a LINE call shows no call mode (review C12).
     * @param confirmedStopped the recorder was asked to stop this very file and confirmed it no longer records
     *   ([com.baba.callvault.services.recording.OrphanRecorderSession]). The 90 s quiet wait,
     *   which only exists to guess that, does not apply; the short [CONFIRMED_QUIET_MS] does. A live recording here wins.
     */
    fun decide(
        hasNote: Boolean,
        sizeBytes: Long,
        ageMs: Long,
        callActive: Boolean,
        ownedByLiveRecording: Boolean = false,
        confirmedStopped: Boolean = false,
    ): RescueDecision = when {
        ownedByLiveRecording -> RescueDecision.WAIT
        // A call that is up holds even a stopped file back: publishing would start Whisper and the Drive copy under it.
        callActive -> RescueDecision.WAIT
        !confirmedStopped && ageMs < QUIET_MS -> RescueDecision.WAIT
        // The recorder answers "not recording" before the muxer's trailer is written (stopRecording returns after
        // its 6 s wait even if teardown still runs). A short quiet guard keeps a truncated copy from being published.
        confirmedStopped && ageMs < CONFIRMED_QUIET_MS -> RescueDecision.WAIT
        // A note is what proves this file is an interrupted recording (the publish path deletes the temp
        // on success, so a note-bearing temp that survived is genuinely a cut-off). Empty-vs-note order:
        // an empty note-bearing temp still holds no audio, so cleaning it is right.
        hasNote && sizeBytes <= 0L -> RescueDecision.DISCARD_EMPTY
        hasNote -> RescueDecision.SAVE_NAMED
        // No note: unclassifiable. Leave it — do not publish, do not notify, do not delete (it may hold
        // audio from an old-build cut-off the user could still want).
        else -> RescueDecision.LEAVE_UNATTRIBUTED
    }

    /** Slack around a call's logged start/end: the log's clock, ringing and hang-up are all a little off. */
    private const val CALL_MATCH_SLACK_MS = 60_000L

    /** How long before the recording started its call may have started: ringing plus answering. */
    private const val MAX_RING_BEFORE_RECORDING_MS = 5 * 60_000L

    /**
     * The call-log entry a cut-off recording belongs to: the call that was up at [cutAt] (the file's last
     * write) and, when the note says when the recording started, one that began shortly before that. The
     * newest wins. Null when no call fits — then nothing about any call is claimed.
     */
    fun matchCall(entries: List<CallLogEntry>, recordingStartedAt: Long?, cutAt: Long): CallLogEntry? =
        entries.filter { call ->
            val end = call.startedAt + call.durationSeconds * 1_000L
            val upAtCut = call.startedAt - CALL_MATCH_SLACK_MS <= cutAt && cutAt <= end + CALL_MATCH_SLACK_MS
            val startsWithRecording = recordingStartedAt == null ||
                call.startedAt in (recordingStartedAt - MAX_RING_BEFORE_RECORDING_MS)..(recordingStartedAt + CALL_MATCH_SLACK_MS)
            upAtCut && startsWithRecording
        }.maxByOrNull { it.startedAt }

    /**
     * The call a SAVED-IN-FULL file belongs to: it started with the recording and ended before the cut (a recording that
     * ran on past its call's end has its last write long after the call, so "up at the cut" would miss it). The newest wins.
     */
    fun matchCompleteCall(entries: List<CallLogEntry>, recordingStartedAt: Long?, cutAt: Long): CallLogEntry? =
        entries.filter { call ->
            val end = call.startedAt + call.durationSeconds * 1_000L
            val startsWithRecording = recordingStartedAt == null ||
                call.startedAt in (recordingStartedAt - MAX_RING_BEFORE_RECORDING_MS)..(recordingStartedAt + CALL_MATCH_SLACK_MS)
            startsWithRecording && end <= cutAt + CALL_MATCH_SLACK_MS
        }.maxByOrNull { it.startedAt }
}
