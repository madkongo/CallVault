/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R11: the log line for each post of the recorder-status notice, written once per change, never per tick. */
class NoticePostLogTest {

    @Test
    fun `the first post is logged with its notice and reason`() {
        val line = NoticePostLog().lineIfChanged("WD_OFF_BY_USER", "content differs")
        assertNotNull(line)
        assertTrue(line!!.contains("WD_OFF_BY_USER"))
        assertTrue(line.contains("content differs"))
    }

    @Test
    fun `the same notice and reason again is not logged`() {
        val log = NoticePostLog()
        log.lineIfChanged("STARTING", "content differs")
        assertNull(log.lineIfChanged("STARTING", "content differs"))
        assertNull(log.lineIfChanged("STARTING", "content differs"))
    }

    @Test
    fun `a change of notice is logged and names the previous one`() {
        val log = NoticePostLog()
        log.lineIfChanged("STARTING", "content differs")
        val line = log.lineIfChanged("NEEDS_WIFI_TO_RESTART", "content differs")
        assertNotNull(line)
        assertTrue(line!!.contains("STARTING"))
        assertTrue(line.contains("NEEDS_WIFI_TO_RESTART"))
    }

    @Test
    fun `a change of reason alone is logged`() {
        val log = NoticePostLog()
        log.lineIfChanged("READY", "content differs")
        assertNotNull(log.lineIfChanged("READY", "nothing was posted under the id"))
    }

    @Test
    fun `going back to an earlier notice is logged again`() {
        val log = NoticePostLog()
        log.lineIfChanged("A", "r")
        log.lineIfChanged("B", "r")
        assertEquals(true, log.lineIfChanged("A", "r") != null)
    }
}
