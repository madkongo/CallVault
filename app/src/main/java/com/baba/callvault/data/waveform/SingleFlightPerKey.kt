/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.waveform

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs a "rewrite this from the current state" job for a key, one at a time, folding a burst into one rerun.
 *
 * The job must read the latest state itself when it runs (the sidecar file is rebuilt from the database),
 * so a request that arrives while the job is running does not need its own run: the running one makes one
 * more pass after it finishes. That keeps writes for one key ordered (an older pass can never land after a
 * newer one) and turns a burst of note saves into at most two rewrites of the file (C74).
 *
 * A caller that finds the job already running returns at once; the one that started it does the work.
 * The work itself is not cancelled halfway, so a request folded into it is never lost to a cancelled caller.
 */
internal class SingleFlightPerKey {

    private val lock = Any()
    private val running = HashSet<String>()
    private val again = HashSet<String>()

    suspend fun run(key: String, job: suspend () -> Unit) {
        val starter = synchronized(lock) {
            if (running.add(key)) {
                true
            } else {
                again.add(key)
                false
            }
        }
        if (!starter) return

        withContext(NonCancellable) {
            var released = false
            try {
                while (true) {
                    synchronized(lock) { again.remove(key) }
                    job()
                    released = synchronized(lock) {
                        // Decided and released under one lock, so a request cannot slip in between.
                        val wanted = again.contains(key)
                        if (!wanted) running.remove(key)
                        !wanted
                    }
                    if (released) break
                }
            } finally {
                // Only when the job threw: after a clean release another starter may already own the key.
                if (!released) {
                    synchronized(lock) {
                        running.remove(key)
                        again.remove(key)
                    }
                }
            }
        }
    }
}
