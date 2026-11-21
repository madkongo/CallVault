/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * What the end of a carrier call reports when it left no file to route (review C37). A capture that wrote nothing
 * used to end in silence: no notice, no health row, so the failure was invisible and the statistics undercounted it.
 * Reporting only; nothing here changes how the recording is stopped or saved. Pure, for testing.
 */
object NoFileReport {

    data class Plan(val notify: Boolean, val recordHealth: Boolean)

    /**
     * @param capturedNothing the engine finished with a staged recording that held no audio.
     * @param captureNeverStartedNotified the "capture never started" notice already told the user, so a second one
     *   (same notification id) would only replace it.
     * @param capturedMs how long the capture ran before the stop.
     */
    fun plan(capturedNothing: Boolean, captureNeverStartedNotified: Boolean, capturedMs: Long = MIN_REPORTABLE_MS): Plan {
        val reportable = capturedNothing && capturedMs >= MIN_REPORTABLE_MS
        return Plan(notify = reportable && !captureNeverStartedNotified, recordHealth = reportable)
    }

    /**
     * A call stopped before this long ends with nothing on it for an ordinary reason (hung up as it connected, a stop
     * during start-up), and is not a failure to announce. Matches the shortest answered call the call-log sweep judges.
     */
    const val MIN_REPORTABLE_MS = 5_000L
}
