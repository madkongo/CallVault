/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import androidx.core.content.UnusedAppRestrictionsConstants as C
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneProfileReaderTest {
    @Test fun confirmedRestrictionIsYes() {
        listOf(C.API_30, C.API_31, C.API_30_BACKPORT).forEach { assertEquals(Tri.YES, PhoneProfileReader.hibernationOf(it)) }
    }

    @Test fun removedOrDisabledFeatureIsNo() {
        listOf(C.FEATURE_NOT_AVAILABLE, C.DISABLED).forEach { assertEquals(Tri.NO, PhoneProfileReader.hibernationOf(it)) }
    }

    @Test fun errorTimeoutOrUnknownCodeIsUnknown() {
        listOf(C.ERROR, null, 99).forEach { assertEquals(Tri.UNKNOWN, PhoneProfileReader.hibernationOf(it)) }
    }
}
