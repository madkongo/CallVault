/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.os.SystemClock

/**
 * How long each step of one Drive copy took, in milliseconds, in the order they ran.
 *
 * Exists because a user's log once showed a Drive backlog crawling at ~10 files an hour with nothing to say
 * which step ate the time. One line per copy now answers that: see [DriveCopyJournal].
 */
class CopyTimings {
    private val steps = ArrayList<Pair<String, Long>>()

    /** Runs [block] and records how long it took under [step]. */
    inline fun <T> time(step: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            add(step, SystemClock.elapsedRealtime() - start)
        }
    }

    fun add(step: String, ms: Long) {
        steps += step to ms
    }

    fun asMap(): Map<String, Long> = steps.toMap()

    /** `lookup=12ms create=930ms stream=4100ms`, or an empty string when nothing was timed. */
    override fun toString(): String = steps.joinToString(" ") { "${it.first}=${it.second}ms" }
}
