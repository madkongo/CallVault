/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import com.baba.callvault.R
import com.baba.callvault.system.phone.BatteryAdvice.Maker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BatteryAdviceTest {
    @Test
    fun `each maker gets its own wording and only OnePlus gets the OnePlus one`() {
        val cases = mapOf(
            "OnePlus" to Maker.ONEPLUS, "Xiaomi" to Maker.XIAOMI, "samsung" to Maker.SAMSUNG, "HONOR" to Maker.HUAWEI_HONOR,
            "HUAWEI" to Maker.HUAWEI_HONOR, "vivo" to Maker.VIVO, "OPPO" to Maker.OPPO_REALME, "realme" to Maker.OPPO_REALME,
            "Google" to Maker.OTHER,
        )
        cases.forEach { (m, expected) -> assertEquals(m, expected, BatteryAdvice.makerOf(m)) }
        assertNotEquals(R.string.permission_battery_oem_hint, BatteryAdvice.hintRes(Maker.XIAOMI))
        assertNotEquals(R.string.permission_battery_oem_hint, BatteryAdvice.hintRes(Maker.OTHER))
    }

    @Test
    fun `a missing manufacturer falls back to the generic advice, and brand can name the maker`() {
        assertEquals(Maker.OTHER, BatteryAdvice.makerOf(null))
        assertEquals(Maker.XIAOMI, BatteryAdvice.makerOf("Unknown", "POCO"))
    }
}
