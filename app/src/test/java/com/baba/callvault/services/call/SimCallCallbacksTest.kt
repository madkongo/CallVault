/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimCallCallbacksTest {

    @Test
    fun one_busy_subscription_names_that_slot() {
        val states = mapOf(
            1 to TelephonyManager.CALL_STATE_IDLE,
            2 to TelephonyManager.CALL_STATE_OFFHOOK,
        )
        assertEquals(2, SimCallCallbacks.singleBusy(states))
    }

    @Test
    fun both_busy_is_not_a_slot() {
        val states = mapOf(
            1 to TelephonyManager.CALL_STATE_OFFHOOK,
            2 to TelephonyManager.CALL_STATE_OFFHOOK,
        )
        assertNull(SimCallCallbacks.singleBusy(states))
    }
}
