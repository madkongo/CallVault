/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessDebuggingModeTest {

    @Test
    fun `each choice reads back from the switches it stores`() {
        WirelessDebuggingMode.entries.forEach {
            assertEquals(it, WirelessDebuggingMode.of(it.enforced, it.neverTurnOff))
        }
    }

    @Test
    fun `the defaults are only when needed`() {
        assertEquals(WirelessDebuggingMode.WHEN_NEEDED, WirelessDebuggingMode.of(enforced = false, neverTurnOff = false))
    }

    @Test
    fun `the old turn-back-on switch alone shows as keep on even if I switch it off`() {
        assertEquals(WirelessDebuggingMode.KEEP_ON_ALWAYS, WirelessDebuggingMode.of(enforced = true, neverTurnOff = false))
    }
}
