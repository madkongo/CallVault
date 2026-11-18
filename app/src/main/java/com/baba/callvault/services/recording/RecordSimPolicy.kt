/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.calls.SimChoice
import com.baba.callvault.data.calls.SimHit

/**
 * Auto-record is All SIMs or one SIM.
 *
 * A call whose SIM cannot be read is recorded when the chosen SIM is still in the phone.
 * A known other SIM, or a missing chosen SIM, is not started; the user can press Record.
 */
internal object RecordSimPolicy {

    enum class Decision {
        /** Start recording and announce it. */
        RECORD,

        /** Do not start. Show Record. */
        OFFER,
    }

    fun decide(choice: SimChoice, hit: SimHit): Decision = when (choice) {
        SimChoice.All -> Decision.RECORD
        is SimChoice.Only -> when (hit) {
            SimHit.CHOSEN, SimHit.UNKNOWN -> Decision.RECORD
            SimHit.OTHER -> Decision.OFFER
        }
    }

    /** Toast and vibration. Only an automatic start. */
    fun announcesStart(decision: Decision): Boolean = decision == Decision.RECORD
}
