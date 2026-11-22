/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.baba.callvault.integrations.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WdEnableForbiddenTest {

    @Test
    fun `enabling is allowed by default`() {
        assertTrue(AdbShell.wirelessDebuggingEnableAllowed())
    }

    @Test
    fun `enabling is forbidden inside the block and allowed again after, even when it throws`() {
        var inside = true
        AdbShell.withoutEnablingWirelessDebugging { inside = AdbShell.wirelessDebuggingEnableAllowed() }
        assertFalse(inside)
        runCatching { AdbShell.withoutEnablingWirelessDebugging { error("boom") } }
        assertTrue(AdbShell.wirelessDebuggingEnableAllowed())
    }
}
