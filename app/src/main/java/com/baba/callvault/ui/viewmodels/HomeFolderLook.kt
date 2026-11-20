/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.ui.viewmodels

import android.content.Context
import com.baba.callvault.system.storage.FolderWatchPlan
import com.baba.callvault.system.storage.RecordingFolderCheck

/**
 * Home's look at the recording folder (review C98): only when the stored answer is stale
 * ([FolderWatchPlan.HOME_MAX_AGE_MS] when good, backing off when bad), on the check's own thread, never holding the UI.
 */
internal object HomeFolderLook {
    /**
     * @return whether a look was started. [onDone] then runs on the check's thread when it ends, whatever it found:
     * the stored answer may have been refreshed by another check that ran first, and Home re-reads its status from
     * that stored answer either way (a cheap read).
     */
    fun onVisible(context: Context, onDone: () -> Unit): Boolean =
        RecordingFolderCheck.checkIfStale(context, "Home", FolderWatchPlan.HOME_MAX_AGE_MS) { onDone() }
}
