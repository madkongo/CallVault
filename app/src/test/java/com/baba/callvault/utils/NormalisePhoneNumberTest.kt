/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import com.baba.callvault.utils.PhoneNumberManager.Companion.normalisePhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Test

class NormalisePhoneNumberTest {

    @Test
    fun `strips formatting and keeps a leading plus`() {
        assertEquals("+12025550173", normalisePhoneNumber("+1 (202) 555-0173"))
        assertEquals("2025550173", normalisePhoneNumber("202-555-0173"))
    }

    @Test
    fun `blank and non-number input give an empty string`() {
        assertEquals("", normalisePhoneNumber("   "))
        assertEquals("", normalisePhoneNumber("anonymous"))
    }

    @Test
    fun `Persian Arabic-Indic and Bengali digits become ASCII (C81)`() {
        assertEquals("+972500000000", normalisePhoneNumber("+۹۷۲۵۰۰۰۰۰۰۰۰"))
        assertEquals("0500000000", normalisePhoneNumber("٠٥٠٠٠٠٠٠٠٠"))
        assertEquals("0123456789", normalisePhoneNumber("০১২৩৪৫৬৭৮৯"))
    }
}
