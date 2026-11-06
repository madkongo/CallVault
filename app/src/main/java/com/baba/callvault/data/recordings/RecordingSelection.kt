/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource

/** Which copies of a recording a bulk delete should remove. */
enum class DeleteScope { DEVICE, DRIVE, BOTH }

/**
 * Turning a multi-selection into the exact list of files to act on.
 *
 * Kept away from the UI and free of Compose because getting it wrong deletes the wrong recordings.
 * The awkward case is that one row does not mean one file: a recording present both on the device
 * and in a Drive folder is shown as a single row carrying two URIs, so "delete these four" is
 * ambiguous until the user says which copies they mean.
 */
object RecordingSelection {

    /**
     * Whether the delete needs to ask which copies to remove.
     *
     * Only true when the selection actually contains a recording held in both places — asking
     * otherwise would be a pointless dialog in front of an unambiguous action.
     */
    fun needsScopeChoice(selected: List<RecordingItem>): Boolean =
        selected.any { it.source == RecordingSource.BOTH }

    /**
     * The files a delete should remove, given the user's [scope] choice.
     *
     * **[scope] is a filter on location, and it applies to every selected recording** — not only to
     * the two-copy ones. "Device only" therefore leaves a Drive-only recording completely untouched,
     * because there is no device copy of it to delete.
     *
     * An earlier version made the scope govern two-copy recordings alone and deleted single-copy
     * ones whatever was chosen, on the reasoning that the user had selected them. That is wrong in
     * the way that matters: picking the *most restrictive* option would have destroyed a file the
     * option said nothing about. When a delete is ambiguous, the reading that removes less wins.
     *
     * A recording carrying neither URI is a shape the repository does not produce; it falls back to
     * the primary URI under [DeleteScope.BOTH] so a selection can never silently delete nothing.
     */
    fun urisToDelete(selected: List<RecordingItem>, scope: DeleteScope): List<Uri> =
        selected.flatMap { item -> urisFor(item, scope) }.distinct()

    private fun urisFor(item: RecordingItem, scope: DeleteScope): List<Uri> {
        if (item.localUri == null && item.driveUri == null) {
            return if (scope == DeleteScope.BOTH) listOf(item.uri) else emptyList()
        }
        return when (scope) {
            DeleteScope.DEVICE -> listOfNotNull(item.localUri)
            DeleteScope.DRIVE -> listOfNotNull(item.driveUri)
            DeleteScope.BOTH -> listOfNotNull(item.localUri, item.driveUri)
        }
    }

    /** How many of [selected] lose at least one file under [scope] — the "2 of 3" in the picker. */
    fun affectedCount(selected: List<RecordingItem>, scope: DeleteScope): Int =
        selected.count { urisFor(it, scope).isNotEmpty() }

    /**
     * The recordings [scope] would leave completely alone.
     *
     * Named rather than merely counted, so the dialog can say *which* recording survives. Silently
     * skipping a file the user selected for deletion is the failure this exists to prevent: they
     * would walk away believing it was gone.
     */
    fun skipped(selected: List<RecordingItem>, scope: DeleteScope): List<RecordingItem> =
        selected.filter { urisFor(it, scope).isEmpty() }

    /**
     * One URI per selected recording, for sharing.
     *
     * The device copy wins where there is a choice: it needs no network to read, and the two copies
     * are the same audio. Sharing both would attach the same call twice.
     */
    fun urisToShare(selected: List<RecordingItem>): List<Uri> =
        selected.map { shareUriOf(it) }.distinct()

    /** The copy a share sends: the device copy when there is one, else Drive's. */
    fun shareUriOf(item: RecordingItem): Uri = item.localUri ?: item.driveUri ?: item.uri
}
