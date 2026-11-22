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

/** L4: a property the app cannot read is not asked again on every snapshot (each ask can log an SELinux denial). */
class UnreadablePropCacheTest {

    @Test
    fun `a property that read empty is not read again, one that read a value always is`() {
        val cache = UnreadablePropCache()
        var reads = 0
        val read = { key: String -> reads++; if (key == "sys.usb.state") "" else "mtp,adb" }
        assertEquals("", cache.get("sys.usb.state", read))
        assertEquals("", cache.get("sys.usb.state", read))
        assertEquals(1, reads)
        assertEquals("mtp,adb", cache.get("sys.usb.config", read))
        assertEquals("mtp,adb", cache.get("sys.usb.config", read))
        assertEquals(3, reads)
    }
}
