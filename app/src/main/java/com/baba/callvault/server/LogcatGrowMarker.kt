/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.io.File

/**
 * Review R7 step 3: the recorder enlarges the system log buffer once per boot instead of forking `logcat -G` on every
 * start (a start can repeat many times a day when the recorder keeps dying).
 *
 * The marker holds the boot id the buffer was last enlarged in. Anything unreadable, unwritable or unknown falls back
 * to enlarging, which is exactly what the recorder did before.
 */
class LogcatGrowMarker(
    private val bootId: () -> String?,
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    fun shouldGrow(): Boolean {
        val boot = runCatching(bootId).getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val marked = runCatching(read).getOrNull()?.trim()
        return marked != boot
    }

    /** Records that the buffer was enlarged in this boot. Never throws. */
    fun markGrown() {
        val boot = runCatching(bootId).getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching { write(boot) }
    }

    companion object {
        private const val BOOT_ID = "/proc/sys/kernel/random/boot_id"
        private const val MARKER = "/data/local/tmp/cv_logcat_grown_boot"

        fun forShell() = LogcatGrowMarker(
            bootId = { File(BOOT_ID).readText() },
            read = { File(MARKER).takeIf { it.exists() }?.readText() },
            write = { File(MARKER).writeText(it) },
        )
    }
}
