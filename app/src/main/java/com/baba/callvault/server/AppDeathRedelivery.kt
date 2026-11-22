/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * The daemon's answer to the app process dying while it records (2026-11-22).
 *
 * The start-of-process hand-over rides on the activity manager's "uid became active" event. That event does not fire
 * when the app's uid never went idle: on the OnePlus 9 the `:drive` process stayed alive, the main process was killed
 * and restarted for a service, and no hand-over ever came, so the restarted app killed the recording daemon. The
 * daemon therefore also watches the app's provider binder and, when it dies while recording, delivers again (a
 * delivery starts the app's process itself). Only while recording: an idle daemon must not restart an app the user
 * stopped.
 */
object AppDeathRedelivery {

    private val DELAYS_MS = longArrayOf(1_000L, 2_000L, 2_000L)

    /** Milliseconds to wait before attempt [attempt] (0-based), or null to stop. */
    fun nextDelayMs(attempt: Int, recording: Boolean, lastDelivered: Boolean?): Long? = when {
        !recording -> null
        lastDelivered == true -> null
        attempt >= DELAYS_MS.size -> null
        else -> DELAYS_MS[attempt]
    }

    /** The uid observer is subscribed to "active" only, but its transaction code must say so; unknown code = accept as before. */
    fun isActiveTransaction(code: Int, activeCode: Int?): Boolean = activeCode == null || code == activeCode
}
