/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which report-only system properties keep reading empty, so a property the app is not allowed to read
 * (each try can log an SELinux denial) stops being asked, instead of on every snapshot (L4). Only after
 * [EMPTY_READS_TO_GIVE_UP] empty reads in a row, so a property that is merely unset for a moment is not lost (R3).
 */
internal class UnreadablePropCache {
    private val emptyReads = ConcurrentHashMap<String, Int>()

    fun get(key: String, read: (String) -> String): String {
        if ((emptyReads[key] ?: 0) >= EMPTY_READS_TO_GIVE_UP) return ""
        val value = read(key)
        if (value.isBlank()) emptyReads.merge(key, 1, Int::plus) else emptyReads.remove(key)
        return value
    }

    companion object {
        const val EMPTY_READS_TO_GIVE_UP = 3
    }
}
