/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrySimTest {

    @Test
    fun the_phone_that_is_dialling_is_that_slot() {
        val dump = """
            Phone Id=0
              mCallState=0
              mForegroundCallState=0
            Phone Id=1
              mCallState=2
              mForegroundCallState=3
        """.trimIndent()
        assertEquals(2, RegistrySim.slotOf(dump))
    }

    @Test
    fun an_idle_foreground_state_is_not_in_the_call_even_if_call_state_is_off_hook() {
        // getCallState() reports off-hook for every SIM. The registry foreground state does not.
        val dump = """
            Phone Id=0
              mCallState=2
              mForegroundCallState=0
            Phone Id=1
              mCallState=2
              mForegroundCallState=1
        """.trimIndent()
        assertEquals(2, RegistrySim.slotOf(dump))
    }

    @Test
    fun both_phones_busy_is_unknown() {
        val dump = """
            Phone Id=0
              mForegroundCallState=1
            Phone Id=1
              mForegroundCallState=1
        """.trimIndent()
        assertNull(RegistrySim.slotOf(dump))
    }

    @Test
    fun lines_without_a_phone_header_stay_in_phone_order() {
        assertEquals(1, RegistrySim.slotOf("mForegroundCallState=4\nmForegroundCallState=0\n"))
        assertNull(RegistrySim.slotOf(null))
        assertNull(RegistrySim.slotOf(""))
    }
}
