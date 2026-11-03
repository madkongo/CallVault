/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * How CallVault handles Wireless debugging: one choice in Settings ▸ Connection instead of the two switches
 * "Keep Wireless debugging on for recording" and "Never turn Wireless debugging off", whose names sounded the
 * same (maintainer, 2026-11-03).
 *
 * Stored in the two old preference keys, unchanged, so rolling back loses nothing:
 * `wd_enforced` (turn it back on after the user switches it off) and `wd_never_turn_off`.
 */
enum class WirelessDebuggingMode(val enforced: Boolean, val neverTurnOff: Boolean) {
    /** On for the recorder, off again when not needed; the user's off stays off. The default. */
    WHEN_NEEDED(enforced = false, neverTurnOff = false),

    /** Never switched off by CallVault; the user's off stays off. */
    KEEP_ON(enforced = false, neverTurnOff = true),

    /** Never switched off by CallVault, and switched back on after the user turns it off. */
    KEEP_ON_ALWAYS(enforced = true, neverTurnOff = true);

    companion object {
        /**
         * The choice the two stored switches amount to. "Turn back on" alone (enforced, not never-off) was possible
         * with the old switches; its closest choice is [KEEP_ON_ALWAYS] — both keep calls recording after the user
         * switches it off. Nothing is rewritten until the user picks something.
         */
        fun of(enforced: Boolean, neverTurnOff: Boolean): WirelessDebuggingMode = when {
            enforced -> KEEP_ON_ALWAYS
            neverTurnOff -> KEEP_ON
            else -> WHEN_NEEDED
        }
    }
}
