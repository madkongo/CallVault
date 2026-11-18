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
import com.baba.callvault.data.calls.SimId
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSimPolicyTest {

    private val chosen = SimChoice.Only(SimId.Sub(4))

    @Test
    fun all_sims_always_auto_records() {
        assertEquals(RecordSimPolicy.Decision.RECORD, RecordSimPolicy.decide(SimChoice.All, SimHit.OTHER))
        assertEquals(RecordSimPolicy.Decision.RECORD, RecordSimPolicy.decide(SimChoice.All, SimHit.UNKNOWN))
        assertEquals(true, RecordSimPolicy.announcesStart(RecordSimPolicy.Decision.RECORD))
    }

    @Test
    fun the_chosen_sim_is_auto_recorded_and_the_other_is_manual() {
        assertEquals(RecordSimPolicy.Decision.RECORD, RecordSimPolicy.decide(chosen, SimHit.CHOSEN))
        assertEquals(RecordSimPolicy.Decision.OFFER, RecordSimPolicy.decide(chosen, SimHit.OTHER))
    }

    @Test
    fun an_unknown_sim_is_recorded_when_the_choice_is_still_one_sim() {
        assertEquals(RecordSimPolicy.Decision.RECORD, RecordSimPolicy.decide(chosen, SimHit.UNKNOWN))
    }
}
