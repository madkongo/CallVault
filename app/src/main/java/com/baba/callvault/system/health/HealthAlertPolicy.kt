/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

/**
 * Whether a warning that is posted again should make a sound (review R31).
 *
 * A warning the user swiped away is gone, so posting it again at the next app start or the next daily sweep rang like a
 * new one. Each unneeded ring trains the user to mute the channel that says "nothing is being recorded". A repeat inside
 * [REPEAT_QUIET_MS] of the last time it alerted is still posted, so it is not lost, but without a sound.
 */
object HealthAlertPolicy {

    /** One day: long enough to cover an app start per hour and a daily sweep, short enough that a lasting problem is heard again. */
    const val REPEAT_QUIET_MS = 24L * 60 * 60 * 1000

    /** [lastAlertAtMs] is 0 when it never alerted. A clock that went backwards counts as "long ago". */
    fun shouldAlert(lastAlertAtMs: Long, nowMs: Long): Boolean {
        if (lastAlertAtMs <= 0L) return true
        val since = nowMs - lastAlertAtMs
        return since < 0L || since >= REPEAT_QUIET_MS
    }
}
