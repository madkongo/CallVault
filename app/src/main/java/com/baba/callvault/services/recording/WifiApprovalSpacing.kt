/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Minimum spacing between the keep-alive's "approve the Wi-Fi just joined" runs (review R16), the same 30 s the
 * recorder-side twin keeps. A Wi-Fi that flaps at the edge of range used to start one approval, and one Wireless
 * debugging write, per reconnect. A join inside the window is delayed to its end, never dropped, so the last
 * network joined is always approved.
 */
object WifiApprovalSpacing {
    const val MIN_SPACING_MS = 30_000L

    /** How long to wait before the approval for a join seen now. A clock that went backwards waits only [settleMs]. */
    fun delayMs(nowMs: Long, lastStartedAtMs: Long?, settleMs: Long): Long {
        if (lastStartedAtMs == null || nowMs < lastStartedAtMs) return settleMs
        val untilSpacingEnds = lastStartedAtMs + MIN_SPACING_MS - nowMs
        return maxOf(settleMs, untilSpacingEnds)
    }
}
