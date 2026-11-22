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
 * Remembers which report-only system properties read empty, so a property the app is not allowed to read
 * (each try can log an SELinux denial) is asked once per process, not on every snapshot (L4).
 */
internal class UnreadablePropCache {
    private val unreadable = ConcurrentHashMap.newKeySet<String>()

    fun get(key: String, read: (String) -> String): String {
        if (key in unreadable) return ""
        return read(key).also { if (it.isBlank()) unreadable.add(key) }
    }
}
