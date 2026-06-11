/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

/** How often finished recordings are swept to the cloud (Drive) folder.
 *  IMMEDIATE = per-recording copy (current behavior); DAILY/WEEKLY = batch sweep on a schedule. */
enum class SyncScheduleMode(val key: String) {
    IMMEDIATE("immediate"), DAILY("daily"), WEEKLY("weekly");
    companion object { fun fromKey(k: String?): SyncScheduleMode = entries.firstOrNull { it.key == k } ?: IMMEDIATE }
}
