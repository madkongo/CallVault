/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.utils.LogRedaction
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

    /** The least a single file may take before the sweep gives up on it and moves on. */
    const val MIN_FILE_TIMEOUT_MS = 3 * 60_000L

    /** Extra time granted per megabyte, so a long call is not abandoned for being long. */
    const val PER_MEGABYTE_MS = 3_000L

    /** Files in a row that stalled or lost the copier before the sweep ends the run instead of trying more. */
    const val MAX_CONSECUTIVE_STALLS = 3

    /**
     * How long one file's copy may run before it is left for the next run.
     *
     * A file stuck for 20 minutes (a Honor phone, 2026-11-15) held the whole sweep, including "Stop for now",
     * hostage. Generous for size: three seconds a megabyte is far slower than any link that works at all.
     */
    fun fileTimeoutMs(sizeBytes: Long): Long =
        MIN_FILE_TIMEOUT_MS + (sizeBytes.coerceAtLeast(0L) / (1024 * 1024)) * PER_MEGABYTE_MS

    /**
     * Minutes of the sweep's retry delay per failed attempt (linear: 2, 4, 6 ...). WorkManager's default is
     * exponential from 30 s up to 5 hours, which is how the drive1 build ended up waiting 5 hours after a few
     * failed listings and ignoring a schedule the user had just changed.
     */
    const val SWEEP_BACKOFF_MINUTES = 2L

    /**
     * Attempts a sweep that cannot list the Drive folder gets before it waits for its next scheduled time.
     * With [SWEEP_BACKOFF_MINUTES] that is waits of 2..16 minutes, about 72 minutes in all, never longer.
     */
    const val LIST_FAILURE_MAX_ATTEMPTS = 8

    /** True when a sweep that could not list the folder (attempt [runAttemptCount], 0-based) asks for another try. */
    fun shouldRetryListFailure(runAttemptCount: Int): Boolean = runAttemptCount < LIST_FAILURE_MAX_ATTEMPTS - 1

    /**
     * How long the app waits for the copier process to list the Drive folder. Listing is one query that took
     * 1.3-4.3 s on a 1484-entry folder; 30 s outlasts a slow provider without making a copier that cannot
     * answer cost the three minutes it did on the drive2 build.
     */
    const val LIST_TIMEOUT_MS = 30_000L

    /**
     * How long the copier waits for a cloud provider that answers "still loading" ([android.provider.DocumentsContract.EXTRA_LOADING])
     * to finish the folder: inside [LIST_TIMEOUT_MS], leaving time for the folder check and the reads themselves.
     */
    const val LIST_LOADING_WAIT_MS = 20_000L

    /** Within [LIST_LOADING_WAIT_MS], a loading folder is read again at least this often, notification or not. */
    const val LIST_LOADING_POLL_MS = 2_500L

    /**
     * A Drive document that counts as already holding the recording: its reported length equals the source's.
     * A length of 0 or -1 is what a cloud provider reports for a file still settling; that is not proof, so
     * the recording goes through the copier again, which re-asks and says "unverified" if it stays unsettled.
     */
    fun isVerifiedPresent(existingSize: Long, sourceSize: Long): Boolean =
        sourceSize > 0L && existingSize == sourceSize

    /**
     * Whether the device copy may be deleted now: only when Drive itself reports a document of exactly the
     * source's length. A copy call returning is not proof; neither is an unreported (-1) or zero length,
     * which cloud providers show for a file still settling. Anything else keeps the device copy.
     */
    fun mayDeleteLocal(driveReportedSize: Long, sourceSize: Long): Boolean =
        sourceSize > 0L && driveReportedSize == sourceSize

    /**
     * The Drive size to record on the catalog row: the length Drive itself reported, or null when it reported none
     * (review C7). Never the device file's size: retention takes an equal Drive size as proof the call reached
     * Drive, and copying the device size across would let an upload Drive never confirmed prove itself.
     */
    fun driveSizeToRecord(driveReportedSize: Long): Long? = driveReportedSize.takeIf { it > 0L }

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
     * Only clear a local catalog link when the provider actually deleted the
     * corresponding document. A failed/denied deletion must leave the recording
     * visible on-device even though a cloud copy was also created.
     */
    fun shouldClearLocalReference(deleteRequested: Boolean, deletionSucceeded: Boolean): Boolean =
        deleteRequested && deletionSucceeded

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

    /**
     * Why the Drive sweep must not copy [displayName], or null when it may (review N10): an audio file under
     * [CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES] is a header-only stub from a call that never connected and holds no
     * audio. Ownership is NOT judged here: for copying, the safe direction is to copy (a name the grammar misses, a
     * " (1)" suffix or a changed template must not keep a real recording off Drive). Sidecars are never stubs.
     */
    fun noAudioStubReason(displayName: String, sizeBytes: Long): String? {
        val isAudio = LogRedaction.NAME_EXTENSIONS.any { displayName.endsWith(".$it", ignoreCase = true) }
        return if (isAudio && sizeBytes < CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES) {
            "holds no audio ($sizeBytes bytes, under ${CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES})"
        } else null
    }
}
