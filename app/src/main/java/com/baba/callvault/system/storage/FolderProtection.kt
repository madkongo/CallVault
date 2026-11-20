/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.utils.AppLogger

/**
 * Where the recording-folder protection's parts meet (review C98, N5): the folder check, the calls kept while the
 * folder is not usable, and saving them once it is. Every entry point is safe to call often and never throws.
 */
object FolderProtection {

    private const val TAG = "CV:FolderProtection"

    /** At app start, off the main thread: hooks the check up, keeps the periodic check, saves what is waiting. */
    fun install(context: Context) = guard("install") {
        val app = context.applicationContext
        RecordingFolderCheck.onChecked = ::onChecked
        WaitingRecordingsWorker.ensureWatch(app)
        if (WaitingRecordings.count(app) > 0) WaitingRecordingsWorker.schedule(app)
        RecordingFolderCheck.checkNow(app, "app start")
    }

    /** The user picked a recording folder (Settings, setup wizard, permissions screen). */
    fun onFolderChosen(context: Context) = guard("folder chosen") {
        val app = context.applicationContext
        RecordingFolderCheck.checkAsync(app, "folder chosen")
        WaitingRecordingsWorker.schedule(app)
    }

    /** After every folder check. */
    internal fun onChecked(context: Context, step: FolderCheckPolicy.Step) = guard("after check") {
        if (step.state.kind == FolderReading.Kind.OK && WaitingRecordings.count(context) > 0) {
            AppLogger.i(TAG, "The recording folder is usable and calls are waiting; saving them")
            WaitingRecordingsWorker.schedule(context)
        }
    }

    /** A call was kept in CallVault's own storage. */
    fun onWaitingKept(context: Context) = guard("call kept") {
        // Saved at once if the folder came back during the call; otherwise the worker finds it still unusable.
        WaitingRecordingsWorker.schedule(context)
    }

    /** A call could not be kept: the waiting area is full or the phone is out of space. */
    fun onWaitingRefused(context: Context, admission: WaitingPolicy.Admission) = guard("call refused") {
        AppLogger.w(TAG, "A call could not be kept while the folder is not usable: $admission")
    }

    /** [count] waiting calls were saved into the folder. */
    fun onWaitingSaved(context: Context, count: Int) = guard("calls saved") {
        AppLogger.i(TAG, "$count waiting call(s) saved into the recording folder")
    }

    private inline fun guard(what: String, block: () -> Unit) {
        runCatching(block).onFailure { AppLogger.e(TAG, "Folder protection step '$what' failed: ${it.message}", it) }
    }
}
