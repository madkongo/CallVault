/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem

/**
 * Which recordings a merge may join, kept away from the view model because a merge deletes the parts
 * it consumed: offering the wrong row here is how two unrelated pieces of audio become one file and
 * the originals go.
 *
 * Merging exists for one situation — a call that dropped and was redialled — so the rule is the
 * other calls with the same party, still on the phone. An imported file is neither: it has no party,
 * it was not a call, and joining it to a call would splice the user's own audio onto a conversation
 * and then delete it.
 */
object MergeCandidates {

    /**
     * Whether this row can take part in a merge at all — asked of the row the user picked, and of
     * every row offered to them.
     */
    fun canMerge(item: RecordingItem): Boolean = !ImportedRecording.isImported(item.displayName)

    /**
     * The other calls with [primary]'s number that could continue it, newest first.
     *
     * Filtered to what is actually on the phone: joining copies frame by frame needs the audio here,
     * not in Drive. Unbounded by date on purpose — a cut-off can only ever hide the row someone came
     * looking for.
     */
    fun of(primary: RecordingItem, all: List<RecordingItem>): List<RecordingItem> {
        if (!canMerge(primary)) return emptyList()
        val key = primary.contactName ?: primary.number ?: return emptyList()
        return all.filter { other ->
            other.displayName != primary.displayName &&
                other.localUri != null &&
                canMerge(other) &&
                (other.contactName ?: other.number) == key
        }
    }
}
