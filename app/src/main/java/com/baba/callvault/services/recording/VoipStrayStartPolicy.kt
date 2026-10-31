/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager

/**
 * Whether an app-call start is a real app call or the tail of a carrier call (#45).
 *
 * On Samsung a Wi-Fi call keeps the mode at MODE_IN_COMMUNICATION. At hang-up Telecom drops the call
 * about 1.5 s before the mode leaves, so [VoipTelephonyGate] let a start through and every Wi-Fi call
 * left a stray 1 s `_voip` file. A "carrier episode" latch closes that window: it is set when a start
 * is refused because a carrier call is up, and cleared only when the mode leaves IN_COMMUNICATION.
 */
object VoipStrayStartPolicy {

    enum class Verdict {
        START,
        /** Telecom has a carrier call up. */
        REFUSE_MANAGED_CALL,
        /** Same IN_COMMUNICATION stretch as a carrier call that just ended. */
        REFUSE_CARRIER_EPISODE,
        /** Our carrier recorder is still running. */
        REFUSE_CARRIER_SESSION,
    }

    fun verdict(latched: Boolean, managedCall: Boolean, carrierSession: Boolean): Verdict = when {
        managedCall -> Verdict.REFUSE_MANAGED_CALL
        latched -> Verdict.REFUSE_CARRIER_EPISODE
        carrierSession -> Verdict.REFUSE_CARRIER_SESSION
        else -> Verdict.START
    }

    /** The latch after a mode event: leaving IN_COMMUNICATION ends the episode. */
    fun latchAfterMode(latched: Boolean, mode: Int): Boolean =
        latched && mode == AudioManager.MODE_IN_COMMUNICATION

    /** The latch after a start was judged: a managed-call refusal starts an episode. */
    fun latchAfterVerdict(latched: Boolean, verdict: Verdict): Boolean =
        latched || verdict == Verdict.REFUSE_MANAGED_CALL
}
