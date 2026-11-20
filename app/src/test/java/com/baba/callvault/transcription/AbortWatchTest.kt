/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Review C179: a WorkManager stop cancels the watcher before it can read isStopped; whisper must still hear it. */
@OptIn(ExperimentalCoroutinesApi::class)
class AbortWatchTest {

    private var aborts = 0
    private var samples = 0
    private var stopped = false
    private var owns = true
    private var finished = false

    private suspend fun watch() = runAbortWatch(
        isStopped = { stopped },
        sample = { samples++ },
        requestAbort = { aborts++ },
        ownsEngine = { owns },
        finishedNormally = { finished },
        onAbort = {},
        pollMs = 400L,
    )

    @Test
    fun `a stop seen on a poll aborts once`() = runTest {
        val job = launch { watch() }
        runCurrent()
        stopped = true
        advanceTimeBy(500)
        job.join()
        assertEquals(1, aborts)
    }

    @Test
    fun `a cancellation that never reads isStopped still aborts whisper`() = runTest {
        val job = launch { watch() }
        runCurrent() // the watcher is now suspended in its delay
        job.cancel()
        job.join()
        assertEquals(1, aborts)
    }

    @Test
    fun `the normal end of a batch does not abort`() = runTest {
        val job = launch { watch() }
        runCurrent()
        finished = true
        job.cancel()
        job.join()
        assertEquals(0, aborts)
    }

    @Test
    fun `a stopped run that does not own the engine leaves another run's decode alone`() = runTest {
        owns = false
        val job = launch { watch() }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(0, aborts)
    }

    @Test
    fun `progress is sampled on every poll`() = runTest {
        val job = launch { watch() }
        advanceTimeBy(1_000)
        job.cancel()
        assertEquals(3, samples) // t=0, 400, 800
    }
}
