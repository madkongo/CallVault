/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietReloadGateTest {

    @Test
    fun `a quiet reload may run while the UI is visible`() {
        assertTrue(QuietReloadGate().mayReloadQuietly())
    }

    @Test
    fun `a quiet reload is skipped while no UI is visible`() {
        val gate = QuietReloadGate().apply { setUiVisible(false) }
        assertFalse(gate.mayReloadQuietly())
    }

    @Test
    fun `a quiet reload may run again once the UI is visible again`() {
        val gate = QuietReloadGate().apply { setUiVisible(false); setUiVisible(true) }
        assertTrue(gate.mayReloadQuietly())
    }
}
