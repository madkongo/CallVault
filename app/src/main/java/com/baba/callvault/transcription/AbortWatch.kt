/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * The loop behind [TranscriptionWorker]'s abort watcher, separated so it can be tested.
 *
 * It passes a stop to whisper two ways. By polling [isStopped], as before. And from its own
 * cancellation: WorkManager 2.10 sets `isStopped` and cancels the worker's coroutine in one step, so a
 * watcher suspended in `delay` is cancelled before it can read the flag and the decode ran on to the
 * end of the recording (review C179). The `finally` closes that gap, but only when the run did not
 * end normally and this run is the one that owns the engine ([ownsEngine]) — the abort flag is
 * process-wide, and a stopped sweep must not abort a tapped run's recording.
 */
internal suspend fun runAbortWatch(
    isStopped: () -> Boolean,
    sample: suspend () -> Unit,
    requestAbort: () -> Unit,
    ownsEngine: () -> Boolean,
    finishedNormally: () -> Boolean,
    onAbort: (String) -> Unit,
    pollMs: Long,
) {
    var aborted = false
    try {
        while (coroutineContext.isActive) {
            if (isStopped()) {
                onAbort("Stopped; asking whisper to abort")
                requestAbort()
                aborted = true
                return
            }
            sample()
            delay(pollMs)
        }
    } finally {
        if (!aborted && !finishedNormally() && ownsEngine()) {
            onAbort("Run cancelled before it finished; asking whisper to abort")
            requestAbort()
        }
    }
}
