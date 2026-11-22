/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.server.DaemonReuse.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** BabApps item 115: when the app reuses a running recorder daemon instead of killing it and launching over ADB. */
class DaemonReuseTest {

    private val installed = "/data/app/new/base.apk"

    private fun judge(
        standalone: Boolean = true,
        stopped: Boolean = false,
        held: Boolean = false,
        hostApk: String? = installed,
    ) = DaemonReuse.judgeRedelivery(standalone, stopped, held, hostApk, installed)

    @Test
    fun `a running daemon from the installed APK is reused`() {
        assertEquals(Verdict.ACCEPT, judge())
    }

    @Test
    fun `a daemon from an older APK is not reused, so it is replaced as before`() {
        assertEquals(Verdict.STALE_APK, judge(hostApk = "/data/app/old/base.apk"))
    }

    @Test
    fun `a daemon that does not say which APK it runs is treated as stale`() {
        assertEquals(Verdict.STALE_APK, judge(hostApk = null))
    }

    @Test
    fun `a leftover standalone daemon is not taken in Shizuku mode`() {
        assertEquals(Verdict.WRONG_MODE, judge(standalone = false))
    }

    @Test
    fun `nothing is taken while the user has recording switched off`() {
        assertEquals(Verdict.RECORDING_STOPPED, judge(stopped = true))
    }

    @Test
    fun `the binder already held is not taken twice`() {
        assertEquals(Verdict.ALREADY_HELD, judge(held = true))
    }

    @Test
    fun `a young process waits for the hand-over before going to ADB`() {
        assertEquals(DaemonReuse.REDELIVERY_GRACE_MS, DaemonReuse.waitForRedeliveryMs(processAgeMs = 400, uptimeMs = 3_600_000))
    }

    @Test
    fun `an old process does not wait, the hand-over would already have come`() {
        assertEquals(0L, DaemonReuse.waitForRedeliveryMs(processAgeMs = DaemonReuse.FRESH_PROCESS_MS, uptimeMs = 3_600_000))
    }

    @Test
    fun `just after boot there is no daemon to wait for`() {
        assertEquals(0L, DaemonReuse.waitForRedeliveryMs(processAgeMs = 400, uptimeMs = DaemonReuse.RECENT_BOOT_MS - 1))
    }

    @Test
    fun `no hand-over wait once this process saw its recorder die`() {
        // Review C17: only the daemon running when our process started sends a hand-over, and it is the one that
        // died. The wait only delayed the relaunch a ringing call was blocked on.
        assertEquals(0L, DaemonReuse.waitForRedeliveryMs(processAgeMs = 400, uptimeMs = 3_600_000, sawRecorderDie = true))
        assertEquals(
            DaemonReuse.REDELIVERY_GRACE_MS,
            DaemonReuse.waitForRedeliveryMs(processAgeMs = 400, uptimeMs = 3_600_000, sawRecorderDie = false),
        )
    }
}
