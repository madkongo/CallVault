/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * When a wait for adbd's listener or an mDNS service is pointless because adbd is **persistently** stopped
 * (GH89-4, C65).
 *
 * `service.adb.tcp.port` persists across adbd restarts, so an armed loopback port looks "about to come back"
 * even when adbd is stopped and nothing will bring it back (the Honor BVL-N49 sat like that for ~48 h). But a
 * single stopped reading is normal: a USB change or screen transition restarts adbd and it reads stopped for a
 * moment (M1). So only a stop that has lasted [GIVE_UP_AFTER_MS] ends a wait; a shorter one keeps waiting as before.
 */
internal object StoppedAdbdWaits {
    /** How long adbd must have read stopped, look after look, before a wait for it is abandoned. */
    const val GIVE_UP_AFTER_MS = 4_000L

    /** [stoppedForMs] is how long the stopped streak has run (0 for a first sighting). */
    fun persistentlyStopped(adbd: AdbdState, stoppedForMs: Long): Boolean =
        adbd == AdbdState.STOPPED && stoppedForMs >= GIVE_UP_AFTER_MS
}
