/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimMatchTest {

    private val business = SimCard(label = "Work", slot = 2, subId = 4, iccId = "8997000000000000001", accountId = "4")
    private val personal = SimCard(label = "Personal", slot = 1, subId = 2, iccId = "8997000000000000002", accountId = "2")
    private val installed = listOf(personal, business)

    @Test
    fun keys_round_trip_and_a_bare_slot_is_legacy() {
        assertEquals(SimChoice.All, SimChoice.fromKey(null))
        assertEquals(SimChoice.All, SimChoice.fromKey("all"))
        assertEquals(SimChoice.All, SimChoice.fromKey("none"))
        assertEquals(SimChoice.Only(SimId.Slot(2)), SimChoice.fromKey("2"))
        assertEquals(SimChoice.Only(SimId.Sub(4)), SimChoice.fromKey("sub:4"))
        assertEquals("icc:8997", SimChoice.Only(SimId.Icc("8997")).key)
        assertEquals(SimChoice.All, SimChoice.fromKey("nope"))
        assertEquals("icc", SimChoice.Only(SimId.Icc("8997000000000000001")).logKey())
    }

    @Test
    fun a_renamed_sim_still_matches_the_stored_subscription() {
        val renamed = business.copy(label = "Something else")
        val choice = SimChoice.Only(SimId.Sub(4))
        assertTrue(choice.id.matches(renamed))
        assertEquals(SimHit.CHOSEN, SimMatch.atStart(choice, renamed, listOf(personal, renamed)))
    }

    @Test
    fun the_card_id_follows_the_sim_to_another_slot() {
        val moved = business.copy(slot = 1)
        val otherNowInOldSlot = personal.copy(slot = 2)
        val choice = SimChoice.Only(SimId.Icc(business.iccId!!))
        assertEquals(SimHit.CHOSEN, SimMatch.atStart(choice, moved, listOf(moved, otherNowInOldSlot)))
        assertEquals(SimHit.OTHER, SimMatch.atStart(choice, otherNowInOldSlot, listOf(moved, otherNowInOldSlot)))
    }

    @Test
    fun a_removed_sim_is_not_treated_as_the_call_in_progress() {
        val choice = SimChoice.Only(SimId.Sub(4))
        val onlyPersonal = listOf(personal)
        assertFalse(SimMatch.present(choice, onlyPersonal))
        assertEquals(SimHit.OTHER, SimMatch.atStart(choice, personal, onlyPersonal))
        assertEquals(SimHit.OTHER, SimMatch.atStart(choice, null, onlyPersonal))
    }

    @Test
    fun an_unknown_call_is_kept_while_the_chosen_sim_is_still_installed() {
        val choice = SimChoice.Only(SimId.Sub(4))
        assertEquals(SimHit.UNKNOWN, SimMatch.atStart(choice, null, installed))
        assertEquals(SimHit.UNKNOWN, SimMatch.afterCall(choice, null, installed))
    }

    @Test
    fun a_legacy_slot_is_rewritten_to_the_subscription_once_the_card_is_seen() {
        val legacy = SimChoice.Only(SimId.Slot(2))
        assertEquals(SimChoice.Only(SimId.Icc(business.iccId!!)), SimMatch.stabilize(legacy, installed))
        val noIcc = installed.map { it.copy(iccId = null) }
        assertEquals(SimChoice.Only(SimId.Sub(4)), SimMatch.stabilize(legacy, noIcc))
        assertEquals(legacy, SimMatch.stabilize(legacy, listOf(personal)))
    }

    @Test
    fun after_the_call_the_phone_account_id_is_the_subscription_on_current_android() {
        val choice = SimChoice.Only(SimId.Sub(4))
        assertEquals(SimHit.CHOSEN, SimMatch.afterCall(choice, CallSim(accountId = "4", slot = 2), installed))
        assertEquals(SimHit.OTHER, SimMatch.afterCall(choice, CallSim(accountId = "2", slot = 1), installed))
    }

    @Test
    fun a_redacted_card_id_is_not_stored() {
        assertFalse(SimCards.usableIcc(""))
        assertFalse(SimCards.usableIcc("0000"))
        assertFalse(SimCards.usableIcc("****"))
        assertTrue(SimCards.usableIcc("8997000000000000001"))
        assertNull(SimId.fromKey("icc:"))
    }
}
