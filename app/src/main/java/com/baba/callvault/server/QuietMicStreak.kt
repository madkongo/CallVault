/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * When the VoIP near side has read only zeros long enough to re-take the mic. Not thread-safe: one feeder owns it.
 *
 * Never while the capture is paused (review N20). A paused capture, including an app call held until it is answered,
 * encodes nothing, so a quiet mic costs nothing; re-taking it then only fights the calling app for the mic (37
 * re-takes in 25 s of a held WhatsApp call on the OP9). Chunks read while paused do not count, so the streak starts
 * from zero on resume and a mic still dead after the answer is re-taken [limit] chunks later.
 */
internal class QuietMicStreak(private val limit: Int) {

    private var zeros = 0

    /** True when the mic should be re-taken now; the streak then starts again. */
    fun onChunk(allZero: Boolean, paused: Boolean): Boolean {
        if (paused || !allZero) {
            zeros = 0
            return false
        }
        zeros++
        if (zeros < limit) return false
        zeros = 0
        return true
    }
}
