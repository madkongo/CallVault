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

    /** No note (written before notes existed): publish it under a generic "recovered_" name. */
    SAVE_UNNAMED,

    /** Zero bytes: there is no audio to save, only a file to remove. */
    DISCARD_EMPTY,
}

/** The container a staged file holds, read from its first bytes. */
enum class StagedContainer(val extension: String, val mimeType: String) {
    OGG("ogg", "audio/ogg"),
    MP4("m4a", "audio/mp4"),
    UNKNOWN("bin", "application/octet-stream");

    companion object {
        /** `OggS` at 0 for Ogg; `ftyp` at 4 for MP4/M4A. Anything else is unknown. */
        fun sniff(head: ByteArray): StagedContainer = when {
            head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "OggS" -> OGG
            head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> MP4
            else -> UNKNOWN
        }
    }
}

/**
 * The rules for saving a recording cut off by CallVault being killed (backlog #4, voarch 2026-09-28).
 *
 * Two promises: a recording still being made is never touched, and nothing holding audio is ever deleted.
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
        sizeBytes <= 0L -> RescueDecision.DISCARD_EMPTY
        hasNote -> RescueDecision.SAVE_NAMED
        else -> RescueDecision.SAVE_UNNAMED
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

    /** `recovered_<stamp>.<ext>` for a file that has no note to name it. */
    fun unnamedFileName(stamp: String, container: StagedContainer): String = "recovered_$stamp.${container.extension}"
}
