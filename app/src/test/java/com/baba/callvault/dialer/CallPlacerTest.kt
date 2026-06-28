/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.dialer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPlacerTest {
    @Test fun blank_is_not_dialable() { assertFalse(CallPlacer.isDialable("")) }
    @Test fun digits_are_dialable() { assertTrue(CallPlacer.isDialable("112")) }
    @Test fun sanitizes_display_to_dial_string() {
        assertEquals("+15551234", CallPlacer.normalize(" +1 (555) 1234 "))
    }
}
