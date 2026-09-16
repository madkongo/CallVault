/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.data.recordings.ImportedRecording

/**
 * Whether recordings have stopped reaching Drive.
 *
 * This exists because of one specific way people lose everything: sync dies quietly — a revoked
 * grant, a folder deleted on the other side, a periodic job that stopped being scheduled — and
 * nothing says so. The recordings keep being made, the app keeps looking healthy, and it is only
 * discovered when the phone is replaced and the cloud copy everyone assumed existed does not.
 *
 * It is deliberately **symptom-based**: it asks "are there recordings that should be in Drive and are
 * not, and have they been that way too long", never "did the last upload fail". A cause-based check
 * only fires when an upload is actually attempted, which is exactly what does not happen in the case
 * worth catching. This one notices a sync that has stopped happening at all.
 */
object SyncHealthPolicy {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * How long a recording may sit with no Drive copy before that is evidence of a problem.
     *
     * Derived from the schedule, not a single constant. On WEEKLY it is entirely correct for a
     * recording to be device-only for six days, and a fixed three-day threshold would have warned
     * every single week — a warning that cries wolf is worse than none, because it trains the user
     * to swipe away the one that matters. Each value leaves a full cycle of headroom past the point
     * the copy should have happened.
     */
    fun staleAfterDays(mode: SyncScheduleMode): Int = when (mode) {
        SyncScheduleMode.IMMEDIATE -> 2
        SyncScheduleMode.DAILY -> 3
        SyncScheduleMode.WEEKLY -> 10
    }

    /**
     * Whether a catalogued recording is one this check may hold against Drive at all.
     *
     * Two things disqualify a row, and the second is the interesting one:
     *
     *  - **It has to be missing from Drive.** A row with a Drive copy is proof, not a symptom.
     *  - **It has to be something Drive was ever going to receive.** An imported file is refused by
     *    `CloudCopyPolicy` on purpose — it is the user's own audio and the app does not put it in
     *    their cloud — so it has a device copy and no Drive copy for ever. Counting it would make
     *    this notification tell the user their backup had failed, permanently, because of a
     *    behaviour they asked for. That is the same false positive two users hit on 2.2.0, where a
     *    claim about the present was made from evidence about the past.
     *
     * Kept here rather than inline at the call site so it can be proved without a device, alongside
     * the counting rule it feeds.
     */
    fun countsAsUnsynced(displayName: String, hasLocalCopy: Boolean, hasDriveCopy: Boolean): Boolean =
        hasLocalCopy && !hasDriveCopy && !ImportedRecording.isImported(displayName)

    /**
     * How many of [unsyncedLastModified] are evidence that copying has actually stopped.
     *
     * Age alone is not evidence. The notification tells the user "copying to Drive stopped a while
     * ago", and a single old recording that never made it does not support that sentence — it is a
     * historical gap, not a stall, and everything since may have copied perfectly. Both users who
     * reported this on 2.2.0 had working backups and calls arriving in Drive.
     *
     * So a recording only counts if **nothing newer has reached Drive**. A Drive copy of something
     * more recent is proof that copying ran after this one was made, which settles the question the
     * warning is asking. When nothing has ever reached Drive, [newestSyncedLastModified] is 0 and
     * every stale recording counts — which is the case the check exists to catch.
     *
     * @param unsyncedLastModified       stamps of recordings with a device copy and no Drive copy.
     * @param newestSyncedLastModified   stamp of the most recent recording that HAS a Drive copy,
     *                                   or 0 when none has.
     */
    fun countStalled(
        unsyncedLastModified: List<Long>,
        newestSyncedLastModified: Long,
        mode: SyncScheduleMode,
        now: Long,
    ): Int {
        val cutoff = now - staleAfterDays(mode) * DAY_MS
        // An undated recording (0) is never counted, for the same reason the retention sweep never
        // deletes one: a stamp we do not have is not evidence of age, and manufacturing a warning
        // out of a metadata gap would send the user hunting for a problem that is not there.
        return unsyncedLastModified.count {
            it > 0L && it < cutoff && it > newestSyncedLastModified
        }
    }
}
