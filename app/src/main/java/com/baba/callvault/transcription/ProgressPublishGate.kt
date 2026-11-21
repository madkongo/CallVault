/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Decides whether a progress sample is worth a write to WorkManager's database (review R41).
 *
 * The abort watcher samples every 400 ms, but the figure is a whole percent, so most samples repeat
 * the last one. Every `setProgress` is a database write that wakes every observer; this lets only a
 * different value through. The 400 ms stop check is untouched.
 */
internal class ProgressPublishGate {
    private var last: List<Any>? = null

    /** True when [completed]/[total]/[current]/[percent] differ from what was last published. */
    fun shouldPublish(completed: Int, total: Int, current: String, percent: Int): Boolean {
        val now = listOf(completed, total, current, percent)
        if (now == last) return false
        last = now
        return true
    }
}
