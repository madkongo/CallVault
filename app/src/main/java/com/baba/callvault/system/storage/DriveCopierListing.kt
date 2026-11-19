/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.system.storage

import kotlinx.coroutines.delay

/**
 * Lists the Drive folder through the `:drive` copier, starting a fresh copier when one cannot answer, a bounded
 * number of times per sweep run.
 *
 * There is no in-app fallback any more (review C11). Doing the listing and the copies in the app's own process
 * put a live connection to Google Drive's provider in the process that records calls, and Android kills that
 * process when Drive's dies. The fallback came from one Honor phone whose `:drive` process died while listing
 * (device-quirks U1); the cause found there, a list reply too big for the binder, is fixed by sending the
 * listing through a file. A copier that still cannot list is closed and, once its process has ended, a new one
 * asked; when none can, or the old process lives on because another client holds it, the
 * run ends with WorkManager's bounded linear retry.
 */
internal object DriveCopierListing {

    /** Copier processes a run may try for its listing before it ends and leaves the rest to the next retry. */
    const val ATTEMPTS_PER_RUN = 3

    /** How long to wait, after letting go of a copier, for its `:drive` process to end before binding a new one. */
    const val PROCESS_EXIT_WAIT_MS = 5_000L

    /** How often the process list is read during that wait. */
    const val PROCESS_POLL_MS = 250L

    /** [copier] is the one that listed (or the last one tried); [failures] are the reasons, one per failed attempt. */
    class Outcome(val copier: DriveCopier, val listing: DriveCopyProtocol.Listing?, val failures: List<String>)

    /**
     * [copierPid] is the running `:drive` process's id, or null when there is none. A restart counts only when
     * the failed copier's process has ended: `:drive` lives while ANY client is bound, so with the after-call
     * copy still bound, letting go and binding again would reach the same wedged process (review C11).
     */
    suspend fun list(
        open: () -> DriveCopier,
        copierPid: () -> Int?,
        attempts: Int = ATTEMPTS_PER_RUN,
        exitWaitMs: Long = PROCESS_EXIT_WAIT_MS,
        pollMs: Long = PROCESS_POLL_MS,
        journal: (String) -> Unit,
        tryList: suspend (DriveCopier) -> Pair<DriveCopyProtocol.Listing?, String>,
    ): Outcome {
        val failures = ArrayList<String>()
        var copier = open()
        for (attempt in 1..attempts) {
            val (listing, why) = tryList(copier)
            if (listing != null) return Outcome(copier, listing, failures)
            failures += why
            val pidBefore = copierPid()
            copier.close()
            val last = attempt == attempts
            journal(
                "list through the copier FAILED (attempt $attempt of $attempts in this run): $why; " +
                    if (last) "giving up for this run" else "copier closed, waiting for its process (pid ${pidBefore ?: "none"}) to end",
            )
            if (last) break
            val held = pidBefore != null && !awaitExit(pidBefore, copierPid, exitWaitMs, pollMs)
            if (held) {
                journal(
                    "copier restart SKIPPED: the :drive process (pid $pidBefore) is still running ${exitWaitMs}ms after this run let go, " +
                        "so another client (the after-call copy) holds it and a new bind would reach the same process; " +
                        "no more list attempts this run",
                )
                break
            }
            copier = open()
        }
        return Outcome(copier, null, failures)
    }

    /** True once no `:drive` process with [pid] is running; false when it is still there after [waitMs]. */
    private suspend fun awaitExit(pid: Int, copierPid: () -> Int?, waitMs: Long, pollMs: Long): Boolean {
        var waited = 0L
        while (copierPid() == pid) {
            if (waited >= waitMs) return false
            delay(pollMs)
            waited += pollMs
        }
        return true
    }
}
