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

    /**
     * At process start, off the main thread: hooks the check up and sets the periodic check to what is needed. Looks at
     * the folder only when the stored answer is older than [FolderWatchPlan.APP_START_MAX_AGE_MS] and no call is up, so
     * a process started for a call makes no provider query (review 2026-11-20).
     */
    fun install(context: Context) = guard("install") {
        val app = context.applicationContext
        RecordingFolderCheck.onChecked = ::onChecked
        WaitingRecordingsWorker.applyWatch(app)
        if (WaitingRecordings.count(app) > 0) WaitingRecordingsWorker.schedule(app)
        RecordingFolderCheck.checkIfStale(app, "app start", FolderWatchPlan.APP_START_MAX_AGE_MS)
    }

    /** Installs that were started and have not finished yet: see [awaitInstalledForTest]. */
    private val installsRunning = java.util.concurrent.atomic.AtomicInteger(0)

    /** [install] on its own thread, so the app's start never waits on it. */
    fun installInBackground(context: Context) {
        val app = context.applicationContext
        installsRunning.incrementAndGet()
        runCatching {
            Thread {
                try { install(app) } finally { installsRunning.decrementAndGet() }
            }.apply { isDaemon = true; name = "cv-folder-protection" }.start()
        }.onFailure {
            installsRunning.decrementAndGet()
            AppLogger.e(TAG, "Could not start the folder protection: ${it.message}", it)
        }
    }

    /**
     * Waits until every [installInBackground] started so far has queued its "app start" check: for tests, which
     * otherwise race that check (it can store its answer over what a test arranged). Gives up after [timeoutMs].
     */
    internal fun awaitInstalledForTest(timeoutMs: Long = 10_000L) {
        // Counted in sleeps, not read off a clock: Robolectric stops the clock.
        var left = timeoutMs / 5L
        while (installsRunning.get() > 0 && left-- > 0) Thread.sleep(5L)
    }

    /** The user picked a recording folder (Settings, setup wizard, permissions screen). */
    fun onFolderChosen(context: Context) = guard("folder chosen") {
        val app = context.applicationContext
        RecordingFolderCheck.checkAsync(app, "folder chosen")
        WaitingRecordingsWorker.schedule(app)
    }

    /** After every folder check. */
    internal fun onChecked(context: Context, step: FolderCheckPolicy.Step) = guard("after check") {
        WaitingRecordingsWorker.applyWatch(context)
        // Once per loss, the moment it is confirmed, before any call: not through the missed-call gate (review N5).
        if (step.alert) notify(context, "folder confirmed gone", folderGone = true)
        if (step.state.kind == FolderReading.Kind.OK) {
            if (WaitingRecordings.count(context) > 0) {
                AppLogger.i(TAG, "The recording folder is usable and calls are waiting; saving them")
                WaitingRecordingsWorker.schedule(context)
            } else if (step.cleared) {
                FolderNotice.show(context, FolderNotice.Clear)
            }
        }
    }

    /** A call was kept in CallVault's own storage. */
    fun onWaitingKept(context: Context) = guard("call kept") {
        // At a call's end (never its start), so a provider query is fine. A folder that works (its file could not be
        // made, or one save failed) needs no "choose a folder" notice: the publisher saves the call now.
        // During another call no provider query: the stored answer decides (S6).
        val folderUsable = if (PhoneCallState.isCallUp(context)) {
            RecordingFolderCheck.cached(context)?.kind == FolderReading.Kind.OK
        } else {
            SafHelper.isFolderValid(context, com.baba.callvault.data.AppPreferences(context).getRecordingFolderUri())
        }
        if (folderUsable) {
            RecordingFolderCheck.recordAlwaysOn(context, "a call was kept while the folder looks usable; saving it now, no notice")
        } else {
            notify(context, "a call was kept", folderGone = true)
        }
        WaitingRecordingsWorker.applyWatch(context)
        // Saved at once if the folder came back during the call; otherwise the worker finds it still unusable.
        WaitingRecordingsWorker.schedule(context)
    }

    /** A call could not be kept: the waiting area is full or the phone is out of space. */
    fun onWaitingRefused(context: Context, admission: WaitingPolicy.Admission) = guard("call refused") {
        AppLogger.w(TAG, "A call could not be kept while the folder is not usable: $admission")
        notify(context, "a call could not be kept ($admission)", folderGone = true, refused = admission)
    }

    /** [count] waiting calls were saved into the folder. */
    fun onWaitingSaved(context: Context, count: Int) = guard("calls saved") {
        AppLogger.i(TAG, "$count waiting call(s) saved into the recording folder")
        WaitingRecordingsWorker.applyWatch(context)
        if (WaitingRecordings.count(context) == 0) {
            val shown = FolderNotice.showSaved(context, count)
            RecordingFolderCheck.recordAlwaysOn(context, "notice: $count waiting call(s) saved${if (shown) "" else " (notice NOT shown)"}", important = true)
        } else {
            notify(context, "some calls still waiting", folderGone = RecordingFolderCheck.cached(context)?.confirmedUnusable == true)
        }
    }

    /** Shows the alert for the state now, and says in the always-on log whether it reached the user. */
    private fun notify(context: Context, why: String, folderGone: Boolean, refused: WaitingPolicy.Admission? = null) {
        val count = WaitingRecordings.count(context)
        val bytes = WaitingRecordings.totalBytes(context)
        val notice = FolderNotice.decide(folderGone, count, bytes, refused, FreeSpaceMonitor.isStorageFull(context))
        val shown = FolderNotice.show(context, notice)
        RecordingFolderCheck.recordAlwaysOn(
            context,
            "alert ($why): waiting $count calls, $bytes bytes -> " +
                if (notice is FolderNotice.Post) (if (shown) "shown" else "NOT shown (no notification permission, or posting failed)") else "cleared",
            important = true,
        )
    }

    private inline fun guard(what: String, block: () -> Unit) {
        runCatching(block).onFailure { AppLogger.e(TAG, "Folder protection step '$what' failed: ${it.message}", it) }
    }
}
