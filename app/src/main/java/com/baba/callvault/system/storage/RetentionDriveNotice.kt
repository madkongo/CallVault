/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * Whether to tell the user that retention is keeping recordings on the phone because Drive has not backed them up
 * (owner decision N3). Since review C7 the sweep never deletes a device copy without a proven Drive copy; without a
 * notice, a broken Drive setup would quietly turn the user's retention period into "keep forever". Pure, so the
 * decision is tested at a desk; [com.baba.callvault.system.health.SilentFailureNotifier] shows or clears it.
 */
object RetentionDriveNotice {

    sealed interface Action {
        data class Post(val count: Int) : Action
        data object Clear : Action
    }

    /** Post with the count while anything is kept for Drive; clear as soon as nothing is. */
    fun decide(keptForDrive: Int): Action = if (keptForDrive > 0) Action.Post(keptForDrive) else Action.Clear

    /**
     * The device copies the size cap would have evicted were it not for their missing Drive copy: the same
     * selection with that one protection lifted, narrowed to the copies it protected.
     */
    fun keptByCap(candidates: List<StorageCapPolicy.Candidate>, capBytes: Long): Set<String> {
        val onlyCopies = candidates.filter { it.isOnlyCopy }.mapTo(HashSet()) { it.displayName }
        if (onlyCopies.isEmpty()) return emptySet()
        val withoutDriveRule = candidates.map { it.copy(isOnlyCopy = false) }
        return StorageCapPolicy.selectForEviction(withoutDriveRule, capBytes).filterTo(HashSet()) { it in onlyCopies }
    }
}
