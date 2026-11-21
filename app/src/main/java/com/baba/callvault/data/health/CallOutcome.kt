/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

/** What a finished call proved. */
sealed interface CallOutcome {
    data object Verified : CallOutcome
    data class Failed(val reason: FailureReason) : CallOutcome
}

object CallOutcomes {

    /**
     * Judges a finished call from what the recording path already knows.
     *
     * @param sizeBytes     bytes actually captured (the trusted count, not a cloud provider's length).
     * @param daemonDied    true when the recorder was lost mid-call; it outranks the empty file it caused,
     *                      because "the recorder stopped" is the actionable message.
     * @param farPartyHeard true/false where observable, null where the capture path cannot tell — which
     *                      must not be read as silence. Carrier capture passes null until the follow-on
     *                      plan adds PCM peak detection.
     * @param heardAnything the carrier capture's own silence check (review N28): false when every channel stayed
     *                      silent for the whole judged call, true when anything was heard, null when it was not
     *                      measured (older recorder, scrcpy path, too short a call) — which is never read as silence.
     */
    fun of(
        sizeBytes: Long,
        daemonDied: Boolean,
        farPartyHeard: Boolean?,
        heardAnything: Boolean? = null,
    ): CallOutcome = when {
        daemonDied -> CallOutcome.Failed(FailureReason.DAEMON_DIED)
        sizeBytes <= 0L -> CallOutcome.Failed(FailureReason.EMPTY_FILE)
        // Before "was the far party heard", because a file with no audio in it at all is a bigger
        // problem than which side is on it — and "only your side was recorded" would send the user
        // to entirely the wrong fix.
        sizeBytes < MIN_PLAUSIBLE_BYTES -> CallOutcome.Failed(FailureReason.NO_AUDIO)
        // A file of pure silence is not "Verified": it is the one failure that looks exactly like a success.
        heardAnything == false -> CallOutcome.Failed(FailureReason.NO_SOUND)
        farPartyHeard == false -> CallOutcome.Failed(FailureReason.ONE_SIDED)
        else -> CallOutcome.Verified
    }

    /**
     * Below this, a file is a container header and not a recording.
     *
     * `sizeBytes > 0` was the entire test, and it let seven files of **exactly 98 bytes** through as
     * successful recordings on the maintainer's phone in August 2026 — a bare header with no audio
     * samples. Reporting success there is worse than reporting nothing: the user only finds out when
     * they go looking for a call that mattered, and the research pass of 2026-08-27 found ~86 accounts
     * of exactly that, including court evidence and medical calls.
     *
     * 1 KiB is chosen to sit *below* the smallest real recording rather than near the failures: one
     * second at 8 kbps, the lowest bitrate CallVault offers, is about 1000 bytes of audio before the
     * container is counted. A sub-second call could in principle land under it, but such a file holds
     * nothing usable either, so the label is still honest.
     *
     * This catches a file with no samples. A full-length file of silence is caught by the carrier
     * capture's own peak check ([heardAnything], review N28), not by size.
     */
    private const val MIN_PLAUSIBLE_BYTES = 1_024L
}

/** Persists [outcome] and remembers that this call was observed at all. */
fun SetupHealthStore.record(outcome: CallOutcome, atMillis: Long, label: String?, fingerprint: String) {
    observeCall(atMillis)
    when (outcome) {
        is CallOutcome.Verified -> recordVerified(atMillis, fingerprint)
        is CallOutcome.Failed -> recordFailure(atMillis, outcome.reason, label)
    }
}
