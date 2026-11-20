/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedRunPolicyTest {

    @Test
    fun `an automatic run waits when the phone is severely hot`() {
        assertTrue(UnattendedRunPolicy.shouldDefer(false, PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(UnattendedRunPolicy.shouldDefer(false, PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `an automatic run goes ahead below severe`() {
        assertFalse(UnattendedRunPolicy.shouldDefer(false, PowerManager.THERMAL_STATUS_NONE))
        assertFalse(UnattendedRunPolicy.shouldDefer(false, PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test
    fun `a tapped run is never held for heat`() {
        assertFalse(UnattendedRunPolicy.shouldDefer(true, PowerManager.THERMAL_STATUS_CRITICAL))
    }
}
