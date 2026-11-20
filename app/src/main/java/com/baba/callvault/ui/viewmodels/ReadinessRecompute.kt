/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * When Home's status card must be worked out again without waiting for a resume (review C24): the recorder
 * connecting or going away, and Wi-Fi coming or going. The "Needs Wi-Fi to record" card is decided from both, and
 * was read once, so it stayed red over a recorder that had connected, and a READY card stayed after Wi-Fi went.
 * The stored recording-folder answer is one more: a re-check that confirms the folder gone (or usable again) turns the
 * card at once (OnePlus 9 Pro, 2026-11-20).
 */
object ReadinessRecompute {

    /**
     * One event per change. The current values of [connected] and [folderChanges] are skipped: Home has just computed
     * the status from them.
     */
    fun triggers(connected: Flow<Boolean>, wifiChanges: Flow<Unit>, folderChanges: StateFlow<Long>? = null): Flow<Unit> =
        if (folderChanges == null) merge(connected.drop(1).map { }, wifiChanges)
        else merge(connected.drop(1).map { }, wifiChanges, folderChanges.drop(1).map { })
}
