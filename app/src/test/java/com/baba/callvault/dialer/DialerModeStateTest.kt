/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialerModeStateTest {
    @Test fun effective_requires_pref_and_role() {
        assertTrue(DialerModeState.effective(prefOn = true, roleHeld = true))
        assertFalse(DialerModeState.effective(prefOn = true, roleHeld = false))
        assertFalse(DialerModeState.effective(prefOn = false, roleHeld = true))
    }
    @Test fun banner_shown_when_pref_on_but_role_lost() {
        assertTrue(DialerModeState.shouldShowRoleLostBanner(prefOn = true, roleHeld = false))
        assertFalse(DialerModeState.shouldShowRoleLostBanner(prefOn = false, roleHeld = false))
    }
}
