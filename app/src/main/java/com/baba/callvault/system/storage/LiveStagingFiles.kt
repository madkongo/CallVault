/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.io.File

/**
 * Staged recordings a live recording in THIS process still owns (review C12).
 *
 * [CutOffRescueWorker] takes a staged file that has been quiet for 90 s with no call up for one the app was killed
 * during. A paused recording is quiet too, and a LINE call (item 108) never raises the call mode, so a paused LINE
 * recording was published mid-call and its staging file deleted. A file in this set is never touched by the
 * rescue. In memory only, on purpose: a process the phone killed takes its set with it, so a genuinely orphaned
 * file is still rescued by the next process. The worker runs in the same process as the recorders.
 */
object LiveStagingFiles {

    private val live = HashSet<String>()

    @Synchronized
    fun register(file: File?) {
        file?.let { live += it.absolutePath }
    }

    @Synchronized
    fun release(file: File?) {
        file?.let { live -= it.absolutePath }
    }

    @Synchronized
    fun isLive(file: File): Boolean = file.absolutePath in live

    @Synchronized
    internal fun clearForTest() = live.clear()
}
