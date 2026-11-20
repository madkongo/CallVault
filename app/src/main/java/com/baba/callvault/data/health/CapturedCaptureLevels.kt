/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import com.baba.callvault.server.CaptureLevels

/**
 * What the carrier capture measured, waiting for the call-end code (review N28).
 *
 * The handoff encoder (app process) publishes it itself; for the direct capture the app reads it out of the
 * recorder's diagnostics after the stop and publishes it here. Single-call scope like `CapturedSpeakerTurns`:
 * cleared when a capture starts, consumed on read, so one call's levels can never judge the next call.
 */
object CapturedCaptureLevels {

    @Volatile
    private var pending: CaptureLevels? = null

    fun clear() {
        pending = null
    }

    fun publish(levels: CaptureLevels) {
        pending = levels
    }

    /** The levels of the capture that just ended, once. */
    fun take(): CaptureLevels? {
        val levels = pending
        pending = null
        return levels
    }
}
