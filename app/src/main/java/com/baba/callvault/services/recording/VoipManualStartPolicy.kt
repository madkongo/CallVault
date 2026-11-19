/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * What the "Ask me" prompt's Record tap does ([VoipCallDetector.startNow]).
 *
 * Kept apart from the detector so it can be tested: the detector's state is only reachable through a real
 * AudioManager.
 */
object VoipManualStartPolicy {

    sealed interface Decision {
        /** Nothing to do: no call is up, or it is already being recorded (a late or repeated tap). */
        data object Ignore : Decision

        /**
         * Record the call now. [viaNormalMode] is how the detector recognised it (BabApps item 108): a manual
         * start gets the same handling as an automatic one of the same call (review C26).
         */
        data class Start(val viaNormalMode: Boolean) : Decision

        /**
         * A carrier (managed) call is up: nothing may open the app-call capture now (review C16). The capture
         * would run beside the phone call's recording, and a second voice capture during a phone call drops
         * the user's own side of it (Q39); on the default path the recorder refuses it and a false "app call
         * not recorded" warning follows. The prompt of a LINE call (item 108) can still be up then, because
         * its end needs MODE_NORMAL.
         */
        data object RefuseCarrierCall : Decision
    }

    fun decide(callActive: Boolean, isRecording: Boolean, normalModeCall: Boolean, carrierCallUp: Boolean): Decision = when {
        !callActive || isRecording -> Decision.Ignore
        carrierCallUp -> Decision.RefuseCarrierCall
        else -> Decision.Start(viaNormalMode = normalModeCall)
    }
}
