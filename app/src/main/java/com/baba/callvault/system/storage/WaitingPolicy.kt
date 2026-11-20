/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.io.File

/** The rules for calls kept in CallVault's own storage while the recording folder is not usable (review N5). */
object WaitingPolicy {

    /**
     * The most waiting calls may take. 📐 1 GiB is about 17 hours of 128 kb/s AAC, or far more Opus: days of calls
     * for anyone, yet small enough not to fill a phone that nobody is watching. Calculated, not measured.
     */
    const val CAP_BYTES = 1L * 1024 * 1024 * 1024

    /** From here the alert warns that the cap is near (75 % of [CAP_BYTES]). */
    const val NEARLY_FULL_BYTES = CAP_BYTES / 4 * 3

    /** A new waiting call is refused when the phone's own storage has less free than this: the system needs it. */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    enum class Admission { OK, FULL, NO_SPACE }

    enum class Level { NORMAL, NEARLY_FULL, FULL }

    enum class Publish {
        /** Save it to the folder now. */
        NOW,

        /** It may still be being written: look again later. */
        WAIT,

        /** It holds no audio: remove it. */
        DISCARD_EMPTY,

        /** No note beside it, so no name or type: leave it, never delete audio (logged). */
        LEAVE,
    }

    /** Whether a new call may be kept, given what waits already and the phone's free space. */
    fun admit(totalBytes: Long, freeBytes: Long): Admission = when {
        totalBytes >= CAP_BYTES -> Admission.FULL
        freeBytes in 0 until MIN_FREE_BYTES -> Admission.NO_SPACE
        else -> Admission.OK
    }

    fun level(totalBytes: Long): Level = when {
        totalBytes >= CAP_BYTES -> Level.FULL
        totalBytes >= NEARLY_FULL_BYTES -> Level.NEARLY_FULL
        else -> Level.NORMAL
    }

    /**
     * What to do with one waiting recording once the folder is usable. A recording whose call finished
     * ([finished]) goes at once. One without that mark was cut off (the app was killed mid-call) and follows the
     * cut-off rescue's rule: only when no call is up and it has been quiet for [CutOffRescuePolicy.QUIET_MS].
     * A recording still owned by a live call in this process ([live]) is never touched.
     */
    fun publishDecision(hasNote: Boolean, sizeBytes: Long, finished: Boolean, ageMs: Long, callActive: Boolean, live: Boolean): Publish = when {
        live -> Publish.WAIT
        !finished && (callActive || ageMs < CutOffRescuePolicy.QUIET_MS) -> Publish.WAIT
        !hasNote -> Publish.LEAVE
        sizeBytes <= 0L -> Publish.DISCARD_EMPTY
        else -> Publish.NOW
    }
}

/**
 * The note beside a waiting recording: the [StagingNote] (same format, so the orphan check reads its start time)
 * plus when the call finished. The folder field is informational only: a waiting call is saved to whichever folder
 * is set when it is published.
 */
data class WaitingNote(val note: StagingNote, val finishedAt: Long?) {

    fun encode(): String = note.encode() + (finishedAt?.let { "\nfinished=$it" } ?: "")

    companion object {
        fun decode(text: String): WaitingNote? {
            val note = StagingNote.decode(text) ?: return null
            val finished = text.lineSequence().firstOrNull { it.startsWith("finished=") }?.substringAfter('=')?.toLongOrNull()
            return WaitingNote(note, finished)
        }

        fun read(recording: File): WaitingNote? =
            StagingNote.fileFor(recording).takeIf { it.exists() }?.let { runCatching { decode(it.readText()) }.getOrNull() }

        fun write(recording: File, note: WaitingNote) = StagingNote.fileFor(recording).writeText(note.encode())
    }
}
