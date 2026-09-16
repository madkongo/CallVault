/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.data.recordings.ImportedRecording

/**
 * The two questions the retention sweep asks before deleting anything, kept pure so they can be tested
 * without a device, a folder, or a clock.
 *
 * They are worth isolating because both are load-bearing for permanent deletion, and each has a failure
 * mode that costs a user their recordings: [isExpired] answering yes for a file whose age we never
 * established, or [isEligible] answering yes for a file CallVault did not write.
 */
object RetentionPolicy {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * The instant before which a copy has outlived [days], or null when [days] means keep-forever (0 or
     * less). A null cutoff deletes nothing, which is how "keep forever" is enforced everywhere.
     */
    fun cutoffFor(days: Int, now: Long): Long? = if (days > 0) now - days * DAY_MS else null

    /**
     * Whether a copy last modified at [lastModified] has outlived [cutoff].
     *
     * An undated copy (0) is never expired. That is deliberate and not a technicality: a file we cannot
     * date is not "very old", it is one we know nothing about, and deleting on that basis turns a
     * metadata gap into data loss. The sweep leaves it alone for ever rather than guess.
     */
    fun isExpired(lastModified: Long, cutoff: Long?): Boolean =
        cutoff != null && lastModified > 0L && lastModified < cutoff

    /**
     * Whether a file found in a storage folder — as opposed to in our catalog — may be deleted by age.
     *
     * A storage folder is whichever folder the user picked, up to and including one they keep other audio
     * in. The first gate is the filename template: [startedAtMillis] is parsed from the timestamp CallVault
     * writes at the front of every recording's name, so anything without one was not written by us and is
     * left alone however old it is. Deleting a stranger's file would be a far worse bug than the one that
     * made this sweep read the folders in the first place.
     *
     * The second gate is imports, and it exists because the first one stops covering them. A file the user
     * imported is written by us and carries our timestamp, so the template alone would start letting the
     * sweep delete it — a voice note someone brought in to transcribe, destroyed overnight by a retention
     * period they set for their calls. Retention is a promise about how long a *call* is kept; an import
     * was never a call, and the user still has nowhere else to have put it.
     */
    fun isEligible(displayName: String, startedAtMillis: Long?): Boolean =
        startedAtMillis != null && agesOut(displayName)

    /**
     * Whether age retention applies to [displayName] at all, wherever the file was found.
     *
     * Asked by the **catalogued** half of the sweep, which [isEligible] does not cover: a row in the
     * catalog needs no filename test, because CallVault put it there. It still needs this one.
     *
     * Stamping an import with the import time — which is what catalogues it — was enough to stop a
     * two-year-old voice note being deleted the night it arrived. It is not enough after that: once
     * the retention period elapses, the catalogued pass would delete it like anything else, and for
     * an import that is not the routine loss of a device copy. There is no Drive copy to survive it,
     * because CallVault refuses to make one, so the delete destroys the only copy of something the
     * user handed us, plus its transcript. Retention is a promise about how long a *call* is kept;
     * an import was never a call.
     */
    fun agesOut(displayName: String): Boolean = !ImportedRecording.isImported(displayName)
}
