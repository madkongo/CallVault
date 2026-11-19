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
 * listing through a file. A copier that still cannot list is closed and a new one asked; when none can, the
 * run ends with WorkManager's bounded linear retry.
 */
internal object DriveCopierListing {

    /** Copier processes a run may try for its listing before it ends and leaves the rest to the next retry. */
    const val ATTEMPTS_PER_RUN = 3

    /** Pause after closing a copier, so its process has ended before the next one is bound. */
    const val PAUSE_BETWEEN_ATTEMPTS_MS = 2_000L

    /** [copier] is the one that listed (or the last one tried); [failures] are the reasons, one per failed attempt. */
    class Outcome(val copier: DriveCopier, val listing: DriveCopyProtocol.Listing?, val failures: List<String>)

    suspend fun list(
        open: () -> DriveCopier,
        attempts: Int = ATTEMPTS_PER_RUN,
        pauseMs: Long = PAUSE_BETWEEN_ATTEMPTS_MS,
        journal: (String) -> Unit,
        tryList: suspend (DriveCopier) -> Pair<DriveCopyProtocol.Listing?, String>,
    ): Outcome {
        val failures = ArrayList<String>()
        var copier = open()
        for (attempt in 1..attempts) {
            val (listing, why) = tryList(copier)
            if (listing != null) return Outcome(copier, listing, failures)
            failures += why
            copier.close()
            val last = attempt == attempts
            journal(
                "list through the copier FAILED (attempt $attempt of $attempts in this run): $why; " +
                    if (last) "giving up for this run" else "copier closed, starting a fresh one",
            )
            if (!last) {
                if (pauseMs > 0) delay(pauseMs)
                copier = open()
            }
        }
        return Outcome(copier, null, failures)
    }
}
