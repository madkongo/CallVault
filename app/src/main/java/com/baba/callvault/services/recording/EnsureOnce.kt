/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Runs a piece of setup once per process: again only when asked to ([run] with `force`), or when the last run failed.
 * Two threads racing may both run it, so the work must be idempotent (creating a notification channel is).
 */
internal class EnsureOnce {

    @Volatile
    private var done = false

    /** Runs [work] unless it already succeeded and [force] is false; [work] returns whether it succeeded. */
    fun run(force: Boolean, work: () -> Boolean) {
        if (done && !force) return
        done = work()
    }

    fun reset() {
        done = false
    }
}
