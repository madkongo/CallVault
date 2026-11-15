/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

/**
 * What [HandoffReceiver] does when the capture drain ends while the call is still up.
 *
 * Pure, so the decision and the line it leaves in the always-on timeline are tested without a phone.
 *
 * Why it matters: when the drain ends early and no replacement capture comes, the recording is over, but the held
 * track used to stay started until the end of the call — and its microphone app-op with it (Pixel 8, Android 17: a
 * recording that ended after 3 s left the shell's RECORD_AUDIO running). Every outcome that ends the recording must
 * stop the held track at once; only [REBUILD] and [STOP_REQUESTED] leave that to the code that already handles them.
 */
object HandoffEarlyEnd {

    enum class Outcome(val label: String, val endsRecording: Boolean) {
        /** Ask the daemon for a replacement capture. */
        REBUILD("rebuild", false),

        /** The user's stop landed between drains: a normal end, torn down by the normal path. */
        STOP_REQUESTED("stop requested", false),

        /** The drain call itself threw, or the rebuild budget is spent: the recording ends here. */
        BUDGET_SPENT("no rebuild (budget spent or drain threw)", true),

        /** A rebuild was asked for and no replacement arrived. */
        REBUILD_FAILED("rebuild failed", true),
    }

    /** What to do with a drain that ended abnormally. [drainThrew] is true when the native call produced no exit code. */
    fun decide(drainThrew: Boolean, rearms: Int, maxRearms: Int, stopRequested: Boolean): Outcome = when {
        drainThrew || rearms >= maxRearms -> Outcome.BUDGET_SPENT
        stopRequested -> Outcome.STOP_REQUESTED
        else -> Outcome.REBUILD
    }

    /** The always-on timeline line: why it ended, whether the rebuild worked, how far into the capture, and the stop result. */
    fun timelineLine(exitLabel: String, outcome: Outcome, rearms: Int, maxRearms: Int, elapsedMs: Long, stopAccepted: Boolean?): String =
        "handoff capture ended early: exit=$exitLabel outcome=${outcome.label} rebuilds=$rearms/$maxRearms " +
            "elapsed=${"%.1f".format(elapsedMs / 1000f)}s heldTrackStop=" +
            when (stopAccepted) {
                true -> "accepted"
                false -> "refused"
                null -> "none held"
            }
}
