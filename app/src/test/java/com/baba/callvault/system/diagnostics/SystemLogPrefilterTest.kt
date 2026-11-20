/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import com.baba.callvault.system.diagnostics.SystemLogFilter.Ours
import com.baba.callvault.system.diagnostics.SystemLogFilter.PidWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder used to send the whole main+system log over the binder. With the 16 MB ring it ran past the 1 MB
 * binder limit, the app fell back to its ADB shell — which Shizuku mode refuses — and the report had no main or
 * system log at all (vivo, Shizuku mode, 2026-11-20). The recorder now cuts the log down to what is about us, on
 * the phone, bounded well under the limit, and says how much it left out.
 */
class SystemLogPrefilterTest {

    private val ours = Ours("com.baba.callvault", 10024)

    private val own = "11-20 17:48:48.306 13572 13600 I CV:VoipRec: call ended"
    private val ownRuntime = "11-20 17:48:48.313 13572 13572 E AndroidRuntime: FATAL EXCEPTION: main"
    private val systemAboutUs = "11-20 17:48:48.400  1500  1600 I ActivityManager: Process com.baba.callvault (pid 13572) has died"
    private val otherApp = "11-20 17:48:48.500  4444  4444 I WhatsApp: message from +972 50-123-4567 delivered"
    private val otherSystem = "11-20 17:48:48.600  1500  1600 I ActivityManager: Process com.whatsapp (pid 4444) has died"

    @Test
    fun `keeps what is about us and drops everything else, with a summary of what it read`() {
        val text = SystemLogFilter.prefilter(sequenceOf(own, ownRuntime, systemAboutUs, otherApp, otherSystem), ours, 100_000)
        val parsed = SystemLogFilter.parsePrefiltered(text)!!
        assertEquals(listOf(own, ownRuntime, systemAboutUs), parsed.lines)
        assertEquals(5, parsed.read)
        assertEquals("11-20 17:48:48", parsed.oldest)
        assertEquals("11-20 17:48:48", parsed.newest)
        assertEquals(0, parsed.truncated)
        assertFalse(text.contains("WhatsApp"))
    }

    @Test
    fun `keeps the newest lines within the budget and counts what it left out`() {
        val lines = (0 until 1_000).map { "11-20 17:%02d:%02d.000 13572 13600 I CV:X: line %04d".format(it / 60, it % 60, it) }
        val text = SystemLogFilter.prefilter(lines.asSequence(), ours, maxChars = 5_000)
        val parsed = SystemLogFilter.parsePrefiltered(text)!!
        assertTrue(text.length <= 5_000 + 200)
        assertEquals("the newest line is kept", lines.last(), parsed.lines.last())
        assertEquals(1_000, parsed.read)
        assertEquals(1_000 - parsed.lines.size, parsed.truncated)
        assertTrue(parsed.truncated > 0)
    }

    @Test
    fun `the cut log selects the same lines as the whole log`() {
        val log = listOf(
            own, ownRuntime, systemAboutUs, otherApp, otherSystem,
            "11-20 17:48:48.310  1500  1530 W ActivityManager: Error showing notification for service",
            "11-20 17:48:48.310  1500  1530 W ActivityManager: java.lang.RuntimeException: invalid channel for service notification: Notification(channel=x)",
        )
        val cut = SystemLogFilter.parsePrefiltered(SystemLogFilter.prefilter(log.asSequence(), ours, 100_000))!!.lines
        assertEquals(SystemLogFilter.select(log, ours), SystemLogFilter.select(cut, ours))
    }

    @Test
    fun `a plain logcat dump is not mistaken for a cut one`() {
        assertNull(SystemLogFilter.parsePrefiltered("$own\n$otherApp"))
        assertNull(SystemLogFilter.parsePrefiltered(""))
    }

    @Test
    fun `who we are crosses the binder as digits only, and anything else is refused`() {
        val withExits = Ours("com.baba.callvault", 10024, listOf(PidWindow(13572, 1_000, 2_000), PidWindow(20001, 3_000, 4_000)))
        val arg = SystemLogFilter.encodeOurs(withExits)
        assertEquals(withExits, SystemLogFilter.decodeOurs(arg, "com.baba.callvault"))
        assertEquals(ours, SystemLogFilter.decodeOurs("10024", "com.baba.callvault"))
        listOf(null, "", "10024; rm -rf /", "abc", "10024;13572@1-2;x", "10024;13572@1-2,'").forEach {
            assertNull("accepted: $it", SystemLogFilter.decodeOurs(it, "com.baba.callvault"))
        }
    }
}
