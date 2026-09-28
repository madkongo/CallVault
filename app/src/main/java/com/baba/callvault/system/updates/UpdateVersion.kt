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
 * A suffix after '-' ("1.2.3-rc2", "1.2.3-test1") marks a test build, which comes BEFORE the final X.Y.Z:
 * the final is an update for it, nothing else of the same number is. Until 2026-09-27 the suffix was simply
 * dropped, so a phone on a pre-release never saw the final of the same version.
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
        // Same number: only the final is newer, and only than a test build of it.
        return !isTestBuild(remoteTag) && isTestBuild(installed)
    }

    /** "1.2.3-rc2" and "v1.2.3-test1" are test builds; "1.2.3" is not. */
    private fun isTestBuild(raw: String): Boolean = raw.trim().contains('-')

    /** "v1.2.3-test1" → [1, 2, 3]; null when any numeric part fails to parse. */
    private fun parse(raw: String): List<Int>? {
        val base = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (base.isEmpty()) return null
        val parts = base.split('.').map { it.toIntOrNull() ?: return null }
        return parts
    }
}
