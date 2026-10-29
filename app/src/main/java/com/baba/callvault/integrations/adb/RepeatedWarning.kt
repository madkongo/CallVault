/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Lets a warning that repeats through a long failure through once per [intervalMs], counting the rest.
 *
 * The #65 report carried the same mDNS "nothing found" line every 12 s for 25 minutes, which buried
 * everything else in the log. The first failure is always reported; repeats inside the interval are
 * counted and the count rides on the next line that is let through. A success ([reset]) starts over.
 */
class RepeatedWarning(private val intervalMs: Long) {
    private var lastLoggedAtMs: Long? = null
    private var suppressed = 0

    /**
     * Null when this occurrence should stay quiet; otherwise how many were kept quiet since the last
     * line (0 for the first).
     */
    @Synchronized
    fun next(nowMs: Long): Int? {
        val last = lastLoggedAtMs
        if (last != null && nowMs - last < intervalMs) {
            suppressed++
            return null
        }
        val count = suppressed
        lastLoggedAtMs = nowMs
        suppressed = 0
        return count
    }

    /** True while repeats are being kept quiet — callers use it to quieten their own lead-in lines too. */
    @Synchronized
    fun isQuiet(nowMs: Long): Boolean = lastLoggedAtMs?.let { nowMs - it < intervalMs } ?: false

    @Synchronized
    fun reset() {
        lastLoggedAtMs = null
        suppressed = 0
    }
}
