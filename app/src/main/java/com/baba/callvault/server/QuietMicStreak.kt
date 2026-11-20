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
 * re-takes in 25 s of a held WhatsApp call on the OP9). Chunks read while paused do not start a re-take; after the
 * resume the streak starts from zero, so a mic still dead after the answer is re-taken [limit] chunks later.
 *
 * One exception, at the resume: when the pause ended on at least [limit] zero chunks in a row, the mic was already
 * silent for a full streak (the calling app likely took it during the hold), so the first quiet chunk after the
 * resume re-takes it at once instead of losing another [limit] chunks of the user's voice right after the answer.
 */
internal class QuietMicStreak(private val limit: Int) {

    private var zeros = 0
    private var pausedZeros = 0

    /** True when the mic should be re-taken now; the streak then starts again. */
    fun onChunk(allZero: Boolean, paused: Boolean): Boolean {
        if (paused) {
            pausedZeros = if (allZero) pausedZeros + 1 else 0
            zeros = 0
            return false
        }
        if (pausedZeros >= limit) zeros = limit - 1
        pausedZeros = 0
        if (!allZero) {
            zeros = 0
            return false
        }
        zeros++
        if (zeros < limit) return false
        zeros = 0
        return true
    }
}
