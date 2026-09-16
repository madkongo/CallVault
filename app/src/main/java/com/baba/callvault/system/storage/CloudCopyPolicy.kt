/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.data.recordings.ImportedRecording

/** What an already-present destination file (same display name) means for a pending copy. */
enum class ExistingCopyVerdict {
    /** The recording is already in the cloud folder — do not upload it again. */
    COMPLETE,

    /** A half-written leftover from an interrupted attempt — replace it. */
    PARTIAL,
}

/**
 * The rules that make a cloud copy safe to repeat.
 *
 * Background (field bug, 2026-07-28): every retry of the copy called `createFile` again, so an
 * interrupted upload produced a *second* Drive document instead of resuming — Google Drive then
 * announced "saved a call" long after the call ended, and the folder collected truncated twins. The
 * copy is now idempotent (skip what is already there), atomic (publish under the final name only once
 * the bytes are in), and bounded (give up honestly instead of retrying forever).
 */
object CloudCopyPolicy {

    /** Retries spent on one recording before the copy is reported as failed rather than retried again. */
    const val MAX_ATTEMPTS = 10

    /** Marker for the staging document a copy writes into before it is renamed to the final name. */
    private const val STAGING_MARKER = ".cvpart"

    /**
     * Judges a destination document that already carries the final display name.
     *
     * A size match means the upload finished. A size *mismatch* means an earlier attempt was cut off
     * mid-stream (this is how the field bug left a 6.7 MB twin of a 9.0 MB recording), so it is replaced.
     *
     * An unmeasurable length is deliberately treated as [COMPLETE]: cloud providers report 0 or -1 for a
     * freshly written file while the upload settles, and deleting on that signal is what previously made
     * recordings disappear from Drive (see the note in [RecordingCopyWorker]). Never delete on a guess.
     */
    fun verdict(existingSize: Long, sourceSize: Long): ExistingCopyVerdict =
        if (existingSize > 0L && sourceSize > 0L && existingSize != sourceSize) {
            ExistingCopyVerdict.PARTIAL
        } else {
            ExistingCopyVerdict.COMPLETE
        }

    /**
     * True when [runAttemptCount] (0-based, as WorkManager reports it) is the last attempt this recording
     * gets. The caller then reports a failure instead of asking for yet another retry.
     */
    fun isLastAttempt(runAttemptCount: Int): Boolean = runAttemptCount >= MAX_ATTEMPTS - 1

    /**
     * The staging name a copy of [displayName] is written under until its bytes are all there.
     *
     * The marker goes *before* the extension ("call.ogg" -> "call.cvpart.ogg") so the name still matches
     * the MIME type being created — document providers are free to append an extension of their own to a
     * name that does not, which would leave staging leftovers under an unpredictable name.
     */
    fun stagingNameFor(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) {
            displayName.substring(0, dot) + STAGING_MARKER + displayName.substring(dot)
        } else {
            displayName + STAGING_MARKER
        }
    }

    /** True for a staging name, so leftovers from a killed attempt can be recognised and swept. */
    fun isStagingName(name: String): Boolean = name.contains(STAGING_MARKER)

    /**
     * Whether a file sitting in the recordings folder may be copied to the cloud folder at all.
     *
     * A file the user imported may not. It is their own audio, handed to CallVault to transcribe and
     * nothing else, so uploading it puts it in their Drive on a decision they never made — and under
     * [com.baba.callvault.data.StorageTarget.DRIVE] the upload is what deletes the local original, so
     * the file they imported would leave the phone.
     *
     * Asked by both routes to Drive — the per-recording enqueue and the scheduled sweep — because a
     * recording reaches Drive through whichever of the two the user has on, and a gate on one of them
     * is no gate at all.
     */
    fun mayGoToCloud(displayName: String): Boolean = !ImportedRecording.isImported(displayName)
}
