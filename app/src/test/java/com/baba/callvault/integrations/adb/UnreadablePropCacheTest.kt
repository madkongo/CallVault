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
    fun `a property is given up on only after it reads empty several times, one that reads a value never is`() {
        val cache = UnreadablePropCache()
        var reads = 0
        val read = { key: String -> reads++; if (key == "sys.usb.state") "" else "mtp,adb" }
        repeat(UnreadablePropCache.EMPTY_READS_TO_GIVE_UP) { assertEquals("", cache.get("sys.usb.state", read)) }
        assertEquals(UnreadablePropCache.EMPTY_READS_TO_GIVE_UP, reads)
        // now cached: no more reads
        assertEquals("", cache.get("sys.usb.state", read))
        assertEquals(UnreadablePropCache.EMPTY_READS_TO_GIVE_UP, reads)
        assertEquals("mtp,adb", cache.get("sys.usb.config", read))
        assertEquals("mtp,adb", cache.get("sys.usb.config", read))
        assertEquals(UnreadablePropCache.EMPTY_READS_TO_GIVE_UP + 2, reads)
    }

    @Test
    fun `an empty read followed by a value is not remembered as unreadable`() {
        val cache = UnreadablePropCache()
        val answers = ArrayDeque(listOf("", "mtp", "", "", ""))
        val read = { _: String -> answers.removeFirst() }
        assertEquals("", cache.get("k", read))
        assertEquals("mtp", cache.get("k", read))
        // the empty streak restarted: needs the full count again
        assertEquals("", cache.get("k", read))
        assertEquals("", cache.get("k", read))
    }
}
