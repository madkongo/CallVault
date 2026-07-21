/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

/**
 * Compares release-tag versions ("v1.2.3") against the installed [android.os.Build] version name.
 * Suffixes after '-' (e.g. "1.2.3-test1") are ignored: a test build of X.Y.Z is treated as X.Y.Z.
 * Unparseable input never reports "newer" — a bad tag must not trigger an update.
 */
object UpdateVersion {

    /** True when [remoteTag] (e.g. "v1.2.4") is strictly newer than the [installed] version name. */
    fun isNewer(remoteTag: String, installed: String): Boolean {
        val remote = parse(remoteTag) ?: return false
        val local = parse(installed) ?: return false
        val length = maxOf(remote.size, local.size)
        for (i in 0 until length) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    /** "v1.2.3-test1" → [1, 2, 3]; null when any numeric part fails to parse. */
    private fun parse(raw: String): List<Int>? {
        val base = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (base.isEmpty()) return null
        val parts = base.split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }
}
