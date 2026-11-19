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

    /**
     * [copier] is the one that listed, or the last one tried (still bound: the caller lets go of it); [failures]
     * are the reasons, one per failed attempt.
     */
    class Outcome(val copier: DriveCopier, val listing: DriveCopyProtocol.Listing?, val failures: List<String>)

    /**
     * [copierProcess] reads the `:drive` process from the system's process list. A restart counts only when
     * the failed copier's process has ended: `:drive` lives while ANY client is bound, so with the after-call
     * copy still bound, letting go and binding again would reach the same wedged process (review C11). A process
     * list that cannot be read proves nothing either way, so it also ends this run's attempts.
     * [inCall] says a phone or app call is in progress: no copier is restarted then, so binding and killing
     * processes never churns while the user is on a call; the run leaves it to the next retry.
     */
    suspend fun list(
        open: () -> DriveCopier,
        copierProcess: () -> DriveDiagnostics.CopierProcess,
        inCall: () -> Boolean,
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
            val last = attempt == attempts
            journal("list through the copier FAILED (attempt $attempt of $attempts in this run): $why")
            if (last) {
                journal("giving up listing for this run")
                break
            }
            // Before close(): letting go of the copier ends :drive when no other client holds it, which is the
            // churn a call must not see.
            if (inCall()) {
                journal(
                    "copier restart DEFERRED: a call is in progress; the copier is kept as it is and this run makes no " +
                        "more list attempts (the sweep lets go of it when the run ends, as every run does)",
                )
                break
            }
            val before = copierProcess()
            if (before is DriveDiagnostics.CopierProcess.Unknown) {
                journal(unreadable(before.why, "before letting go of the copier; it is kept as it is"))
                break
            }
            copier.close()
            if (before is DriveDiagnostics.CopierProcess.Running) {
                journal("copier closed, waiting for its process (pid ${before.pid}) to end")
                when (val exit = awaitExit(before.pid, copierProcess, exitWaitMs, pollMs)) {
                    is DriveDiagnostics.CopierProcess.Running -> {
                        journal(
                            "copier restart SKIPPED: the :drive process (pid ${exit.pid}) is still running ${exitWaitMs}ms after this run let go, " +
                                "so another client (the after-call copy) holds it and a new bind would reach the same process; " +
                                "no more list attempts this run",
                        )
                        break
                    }
                    is DriveDiagnostics.CopierProcess.Unknown -> {
                        journal(unreadable(exit.why, "while waiting for the old copier process to end"))
                        break
                    }
                    DriveDiagnostics.CopierProcess.None -> Unit
                }
            } else {
                journal("copier closed; no :drive process was running")
            }
            copier = open()
        }
        return Outcome(copier, null, failures)
    }

    /**
     * Waits up to [waitMs] for the `:drive` process [pid] to end. [DriveDiagnostics.CopierProcess.None] once it has
     * (a different pid counts too: that is a new process), Running([pid]) when it is still there at the end, or
     * Unknown as soon as the process list cannot be read.
     */
    private suspend fun awaitExit(
        pid: Int, copierProcess: () -> DriveDiagnostics.CopierProcess, waitMs: Long, pollMs: Long,
    ): DriveDiagnostics.CopierProcess {
        var waited = 0L
        while (true) {
            when (val now = copierProcess()) {
                is DriveDiagnostics.CopierProcess.Unknown -> return now
                is DriveDiagnostics.CopierProcess.Running -> if (now.pid != pid) return DriveDiagnostics.CopierProcess.None
                DriveDiagnostics.CopierProcess.None -> return now
            }
            if (waited >= waitMs) return DriveDiagnostics.CopierProcess.Running(pid)
            delay(pollMs)
            waited += pollMs
        }
    }

    private fun unreadable(why: String, whenRead: String) =
        "copier restart SKIPPED: process list unreadable $whenRead ($why); whether the old :drive process ended " +
            "cannot be told, so this run makes no more list attempts"
}
