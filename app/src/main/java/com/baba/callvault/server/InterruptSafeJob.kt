/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Wraps a job for the recorder's single worker thread so it starts with a clean interrupt flag (review C171).
 *
 * A stop that overruns its wait interrupts the worker to free a blocked join. If the job had already finished, or the
 * interrupt landed in native code that does not clear it, the flag survives into the NEXT job and could make the next
 * call's scrcpy fallback fail at once. Clearing it before each job removes that leak; an interrupt aimed at the job that
 * is running is raised after it starts, so it is still honoured.
 */
internal object InterruptSafeJob {
    fun wrap(job: () -> Unit): Runnable = Runnable {
        Thread.interrupted()
        job()
    }
}
