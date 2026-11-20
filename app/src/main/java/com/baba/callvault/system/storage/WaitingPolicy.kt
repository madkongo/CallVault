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

    /** The publisher's first retry: long enough for a cut-off waiting call to go quiet. */
    const val FIRST_RETRY_MS = CutOffRescuePolicy.QUIET_MS + 30_000L

    /** Its retries double up to this. */
    const val MAX_RETRY_MS = 60 * 60_000L

    /** After this many retries in a row it stops; the next trigger (app start, Home, a check, a call) starts again. */
    const val MAX_RETRIES = 12

    /** After this many retries, a call state that seems never to end (a stuck audio mode) no longer blocks a finished save. */
    const val IGNORE_CALL_AFTER_ATTEMPTS = 6

    fun retryDelayMs(attempt: Int): Long =
        (FIRST_RETRY_MS shl attempt.coerceIn(0, 20)).coerceAtMost(MAX_RETRY_MS).coerceAtLeast(FIRST_RETRY_MS)

    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_RETRIES

    fun ignoreCallState(attempt: Int): Boolean = attempt >= IGNORE_CALL_AFTER_ATTEMPTS

    /**
     * A fresh "save now" (a folder picked, a call kept, the folder found usable) and the one pending retry are separate
     * unique works, each replacing its own kind (F4, merge gate 2026-11-20): a fresh run starts at once instead of
     * waiting behind a backed-off retry, and a late retry from an older run replaces the pending one instead of adding a
     * second chain. Runs never overlap: [WaitingPublisher.runExclusive].
     */
    fun workName(fresh: Boolean): String = if (fresh) "waiting_recordings_publish" else "waiting_recordings_retry"

    @Suppress("UNUSED_PARAMETER")
    fun workPolicy(fresh: Boolean): androidx.work.ExistingWorkPolicy = androidx.work.ExistingWorkPolicy.REPLACE

    /**
     * The retry count is kept across fresh runs until one needs no retry, so [ignoreCallState] can be reached; and starts
     * again when the retries stop ([MAX_RETRIES]), for the next trigger.
     */
    fun attemptAfterRun(attempt: Int, retryLater: Boolean): Int =
        if (retryLater && shouldRetry(attempt)) attempt + 1 else 0

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
 * plus when the call finished (its after-call steps done) and what the steps at saving time need ([Extras]). The
 * folder field is informational only: a waiting call is saved to whichever folder is set when it is published.
 */
data class WaitingNote(val note: StagingNote, val finishedAt: Long?, val extras: Extras? = null) {

    /**
     * What saving needs that the name does not say: the keep-or-delete question differs for app calls ([isVoip]), the
     * details file names the near channel of an app-call mix ([appCallMix]) and an app call's [direction] token.
     */
    data class Extras(val isVoip: Boolean, val appCallMix: Boolean, val direction: String?)

    fun encode(): String = note.encode() + (finishedAt?.let { "\nfinished=$it" } ?: "") +
        (extras?.let { "\nvoip=${it.isVoip}\nmix=${it.appCallMix}" + (it.direction?.let { d -> "\ndir=$d" } ?: "") } ?: "")

    companion object {
        fun decode(text: String): WaitingNote? {
            val note = StagingNote.decode(text) ?: return null
            fun field(key: String) = text.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
            val voip = field("voip")?.toBooleanStrictOrNull()
            val extras = voip?.let { Extras(it, field("mix")?.toBooleanStrictOrNull() ?: false, field("dir")?.takeIf { d -> d.isNotBlank() }) }
            return WaitingNote(note, field("finished")?.toLongOrNull(), extras)
        }

        fun read(recording: File): WaitingNote? =
            StagingNote.fileFor(recording).takeIf { it.exists() }?.let { runCatching { decode(it.readText()) }.getOrNull() }

        fun write(recording: File, note: WaitingNote) = StagingNote.fileFor(recording).writeText(note.encode())
    }
}
