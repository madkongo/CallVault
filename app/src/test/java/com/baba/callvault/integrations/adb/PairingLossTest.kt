/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When CallVault decides Android has forgotten its pairing (issue #43).
 *
 * The reporter's S24 dropped the pairing after a week and the app retried "ADB not connected" forever,
 * with no way back but a reinstall. The opposite mistake is worse for everyone else: telling a user whose
 * pairing is fine to pair again. So one refusal proves nothing, and any successful connection wipes the
 * count — only a run of refusals with no success between them counts as lost.
 */
class PairingLossTest {

    @Test
    fun a_fresh_install_has_not_lost_its_pairing() {
        assertFalse(PairingLoss.isLost(0))
    }

    @Test
    fun one_or_two_refusals_are_not_enough_to_call_it_lost() {
        // A single TLS "protocol error" can be a flaky handshake; the user must not be asked to re-pair for it.
        assertFalse(PairingLoss.isLost(PairingLoss.afterRefusal(0)))
        assertFalse(PairingLoss.isLost(PairingLoss.afterRefusal(PairingLoss.afterRefusal(0))))
    }

    @Test
    fun three_refusals_in_a_row_mean_the_pairing_is_gone() {
        var refusals = 0
        repeat(PairingLoss.REFUSALS_BEFORE_LOST) { refusals = PairingLoss.afterRefusal(refusals) }
        assertTrue(PairingLoss.isLost(refusals))
    }

    @Test
    fun a_successful_connection_clears_every_refusal_before_it() {
        var refusals = 0
        repeat(10) { refusals = PairingLoss.afterRefusal(refusals) }
        assertEquals(0, PairingLoss.afterConnected())
        assertFalse(PairingLoss.isLost(PairingLoss.afterConnected()))
    }

    @Test
    fun the_count_stops_growing_once_lost_so_it_cannot_overflow_over_months() {
        var refusals = 0
        repeat(100) { refusals = PairingLoss.afterRefusal(refusals) }
        assertEquals(PairingLoss.REFUSALS_BEFORE_LOST, refusals)
    }
}
