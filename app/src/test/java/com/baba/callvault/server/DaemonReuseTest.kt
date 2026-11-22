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

    @Test
    fun `a cleanup that held no binder is not a recorder death`() {
        assertEquals(false, DaemonReuse.recorderDeathEarned(heldABinder = false))
    }

    @Test
    fun `the death of a binder this process held is a recorder death`() {
        assertEquals(true, DaemonReuse.recorderDeathEarned(heldABinder = true))
    }

    @Test
    fun `a fresh process whose flag was not earned still waits for the hand-over`() {
        val sawDie = DaemonReuse.recorderDeathEarned(heldABinder = false)
        assertEquals(DaemonReuse.REDELIVERY_GRACE_MS, DaemonReuse.waitForRedeliveryMs(15, 3_600_000, sawDie))
    }

    @Test
    fun `a running daemon is spared during a call`() {
        assertEquals(DaemonReuse.StaleAction.SPARE, DaemonReuse.staleAction(callInProgress = true, daemonRunning = true))
    }

    @Test
    fun `an unreadable daemon list is treated as running during a call`() {
        assertEquals(DaemonReuse.StaleAction.SPARE, DaemonReuse.staleAction(callInProgress = true, daemonRunning = null))
    }

    @Test
    fun `a call with no daemon running launches one without killing anything`() {
        assertEquals(DaemonReuse.StaleAction.LAUNCH, DaemonReuse.staleAction(callInProgress = true, daemonRunning = false))
    }

    @Test
    fun `without a call stale daemons are killed as before`() {
        assertEquals(DaemonReuse.StaleAction.KILL_THEN_LAUNCH, DaemonReuse.staleAction(callInProgress = false, daemonRunning = true))
        assertEquals(DaemonReuse.StaleAction.KILL_THEN_LAUNCH, DaemonReuse.staleAction(callInProgress = false, daemonRunning = null))
    }

    @Test
    fun `carrier and VoIP audio modes count as a call, ringing and normal do not`() {
        assertEquals(true, DaemonReuse.isCallAudioMode(2))
        assertEquals(true, DaemonReuse.isCallAudioMode(3))
        assertEquals(false, DaemonReuse.isCallAudioMode(0))
        assertEquals(false, DaemonReuse.isCallAudioMode(1))
    }

    @Test
    fun `pgrep output with a pid means a daemon is running, empty output does not`() {
        assertEquals(true, DaemonReuse.pgrepFoundDaemon("27774\n"))
        assertEquals(false, DaemonReuse.pgrepFoundDaemon(""))
        assertEquals(false, DaemonReuse.pgrepFoundDaemon("\n  \n"))
        assertEquals(false, DaemonReuse.pgrepFoundDaemon("sh: pgrep: not found"))
    }
}
