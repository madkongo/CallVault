/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteLogTest {
    @Test
    fun `a refused local audio delete says the file is still there`() {
        val l = DeleteLog.line(DeleteLog.LOCAL, "call_1.m4a", DeleteLog.Outcome.REFUSED)
        assertTrue(l, l.contains("local audio 'call_1.m4a': refused"))
        assertTrue(l, l.contains("still there"))
    }

    @Test
    fun `sidecars are named by extension`() {
        assertEquals("sidecar .md", DeleteLog.kindOf("call_1.md"))
        assertEquals("sidecar .json", DeleteLog.kindOf("call_1.JSON"))
        assertEquals("audio", DeleteLog.kindOf("call_1.m4a"))
        assertEquals("audio", DeleteLog.kindOf(null))
    }

    @Test
    fun `a thrown delete carries the reason and the Drive label`() {
        val l = DeleteLog.line(DeleteLog.DRIVE, "a.m4a", DeleteLog.Outcome.THREW, "boom")
        assertEquals("Delete Drive audio 'a.m4a': failed: boom", l)
    }

    @Test
    fun `a deleted copy reads as deleted`() {
        assertEquals("Delete local sidecar .md 'a.md': deleted", DeleteLog.line(DeleteLog.LOCAL, "a.md", DeleteLog.Outcome.DELETED))
    }
}
