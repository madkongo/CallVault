/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.data

/** Where finished recordings are routed. */
enum class StorageTarget(val key: String) {
    LOCAL("local"), DRIVE("drive"), BOTH("both");
    companion object { fun fromKey(k: String): StorageTarget = entries.firstOrNull { it.key == k } ?: LOCAL }
}
