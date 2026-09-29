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
 * The rules for saving a recording cut off by CallVault being killed (backlog #4, voarch 2026-09-28).
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

    fun decide(hasNote: Boolean, sizeBytes: Long, ageMs: Long, callActive: Boolean): RescueDecision = when {
        callActive -> RescueDecision.WAIT
        ageMs < QUIET_MS -> RescueDecision.WAIT
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
}
