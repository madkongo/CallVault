/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R31: a warning the user swiped must not ring again at every app start or daily sweep. */
class HealthAlertPolicyTest {

    private val day = HealthAlertPolicy.REPEAT_QUIET_MS

    @Test
    fun `the first time a warning is posted it alerts`() {
        assertTrue(HealthAlertPolicy.shouldAlert(lastAlertAtMs = 0L, nowMs = 5_000L))
    }

    @Test
    fun `the same warning inside the quiet window is posted without a sound`() {
        assertFalse(HealthAlertPolicy.shouldAlert(lastAlertAtMs = 1_000L, nowMs = 1_000L + day - 1))
    }

    @Test
    fun `after the quiet window it alerts again`() {
        assertTrue(HealthAlertPolicy.shouldAlert(lastAlertAtMs = 1_000L, nowMs = 1_000L + day))
    }

    @Test
    fun `a clock that went backwards alerts rather than staying silent for ever`() {
        assertTrue(HealthAlertPolicy.shouldAlert(lastAlertAtMs = 9_000_000L, nowMs = 1_000L))
    }
}
