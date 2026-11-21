/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * N30: how often the post-re-take check of the call app may run, so a phone that re-takes the mic 155 times in two
 * minutes (issue #41) does not run 155 `dumpsys audio` reads. One at a time, at least [MIN_GAP_MS] apart, at most
 * [MAX_CHECKS] per call, none once evidence is recorded. Also gives up resolving the call app's uid after
 * [MAX_UID_FAILURES] misses, and says when a state line differs from the last one logged.
 */
internal class MicContentionBudget {
    companion object {
        const val MAX_CHECKS = 10
        const val MIN_GAP_MS = 10_000L
        const val MAX_UID_FAILURES = 2
    }

    private var running = false
    private var started = 0
    private var lastStartMs = Long.MIN_VALUE
    private var evidence = false
    private var uidFailures = 0
    private var lastState: String? = null

    @Synchronized fun tryBegin(nowMs: Long): Boolean {
        if (running || evidence || started >= MAX_CHECKS) return false
        if (lastStartMs != Long.MIN_VALUE && nowMs - lastStartMs < MIN_GAP_MS) return false
        running = true
        started++
        lastStartMs = nowMs
        return true
    }

    @Synchronized fun end() { running = false }

    @Synchronized fun evidenceRecorded() { evidence = true }

    @Synchronized fun mayResolveUid(): Boolean = uidFailures < MAX_UID_FAILURES

    @Synchronized fun uidResolved(found: Boolean) { uidFailures = if (found) 0 else uidFailures + 1 }

    @Synchronized fun stateChanged(key: String): Boolean {
        if (key == lastState) return false
        lastState = key
        return true
    }
}
