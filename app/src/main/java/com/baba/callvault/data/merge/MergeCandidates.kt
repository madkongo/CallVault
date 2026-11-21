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
     * Drive-only calls ARE offered: a DRIVE-storage user has no local copy of any call (it is deleted
     * after upload), so requiring the audio on the phone here hid every candidate and the dialog wrongly
     * said "no other calls with this number". The merge itself downloads a Drive-only part before
     * joining. Unbounded by date on purpose — a cut-off can only ever hide the row someone came looking for.
     */
    fun of(primary: RecordingItem, all: List<RecordingItem>): List<RecordingItem> {
        if (!canMerge(primary)) return emptyList()
        val key = mergeKey(primary) ?: return emptyList()
        return all.filter { other ->
            other.displayName != primary.displayName &&
                canMerge(other) &&
                mergeKey(other) == key
        }
    }

    /**
     * The value two calls must share to be the same party. A contact name is used verbatim; otherwise
     * the number is reduced to its last [TAIL_DIGITS] digits so an incoming "+35679…" and an outgoing
     * "079…" to the same person match even when contact lookup missed and the raw strings differ. A
     * short code (fewer than [TAIL_DIGITS] digits) must match in full. Mirrors ContactIgnoreRule.
     */
    private fun mergeKey(item: RecordingItem): String? {
        item.contactName?.let { return it }
        val raw = item.number?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A template like {date}_{direction}_{contact_name} puts a NAME (or an opaque token) where the parser
        // expects a number. Pulling digits out of it ("[C:4a4b]" -> 44) would join strangers, so anything
        // that is not plainly a number is a name key, compared whole and never turned into a number.
        if (raw.any { !it.isDigit() && it !in NUMBER_PUNCTUATION }) return raw
        val digits = raw.filter { it.isDigit() }.takeIf { it.isNotEmpty() } ?: return null
        return if (digits.length >= TAIL_DIGITS) digits.takeLast(TAIL_DIGITS) else digits
    }

    /** What a phone number may carry besides digits. */
    private const val NUMBER_PUNCTUATION = "+-() ."

    /** Digits a subscriber number keeps across its national and international forms. */
    private const val TAIL_DIGITS = 9
}
