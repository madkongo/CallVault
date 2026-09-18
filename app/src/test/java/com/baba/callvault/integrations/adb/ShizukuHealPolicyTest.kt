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

/**
 * When CallVault starts somebody else's Shizuku server again after killing it, and what it then says.
 *
 * Measured on the OP9 on 2026-09-18: arming off-Wi-Fi recording gives adbd a new pid and the Shizuku
 * server that was running is gone for good. The launcher re-arms by itself after every reboot, so this
 * is not a one-off — which is why the heal is automatic rather than a prompt.
 */
class ShizukuHealPolicyTest {

    @Test
    fun `a server nobody was running is never started`() {
        // The heal only ever puts back what CallVault broke. Starting Shizuku for a user who did not
        // have it running would be CallVault launching another app's privileged service uninvited.
        assertEquals(
            ShizukuHeal.NOTHING,
            ShizukuHealPolicy.decide(wasRunningBefore = false, answersNow = false, recordingLive = false),
        )
        assertEquals(
            ShizukuHeal.NOTHING,
            ShizukuHealPolicy.decide(wasRunningBefore = false, answersNow = true, recordingLive = false),
        )
    }

    @Test
    fun `a server that came back on its own is left completely alone`() {
        // Shizuku's own start-on-boot can win the race. A second server started beside it leaves two
        // shell-uid hosts alive and the app bound to whichever answered last — only-one-recorder-host.
        assertEquals(
            ShizukuHeal.ALREADY_BACK,
            ShizukuHealPolicy.decide(wasRunningBefore = true, answersNow = true, recordingLive = false),
        )
        assertEquals(
            ShizukuHealNotice.NONE,
            ShizukuHealPolicy.notice(ShizukuHeal.ALREADY_BACK, verifiedRunning = true, underDialog = false),
        )
    }

    @Test
    fun `a live recording outranks the heal`() {
        // Starting Shizuku needs a shell over the embedded ADB connection, and ADB work during a capture
        // is what kills the daemon holding it. A missed call cannot be recovered; a Shizuku server is
        // two taps.
        assertEquals(
            ShizukuHeal.TELL_ONLY_RECORDING,
            ShizukuHealPolicy.decide(wasRunningBefore = true, answersNow = false, recordingLive = true),
        )
    }

    @Test
    fun `a server we stopped and that is still down gets started again`() {
        assertEquals(
            ShizukuHeal.START,
            ShizukuHealPolicy.decide(wasRunningBefore = true, answersNow = false, recordingLive = false),
        )
    }

    @Test
    fun `success is claimed only on evidence that it answered`() {
        // The starter exits 0 long before the server is up. "Shizuku is back" without a ping is the
        // Drive-health false positive again: a claim about the present with no evidence about it.
        assertEquals(
            ShizukuHealNotice.RESTARTED,
            ShizukuHealPolicy.notice(ShizukuHeal.START, verifiedRunning = true, underDialog = false),
        )
        assertEquals(
            ShizukuHealNotice.COULD_NOT_RESTART,
            ShizukuHealPolicy.notice(ShizukuHeal.START, verifiedRunning = false, underDialog = false),
        )
    }

    @Test
    fun `a dialog does not silence the outcome of a heal it could not know about`() {
        // The off-Wi-Fi dialog warns that Shizuku will stop. It cannot say whether starting it again
        // worked, so both answers are still posted — including the good one, or the user would open
        // Shizuku to start something that is already running.
        assertEquals(
            ShizukuHealNotice.RESTARTED,
            ShizukuHealPolicy.notice(ShizukuHeal.START, verifiedRunning = true, underDialog = true),
        )
        assertEquals(
            ShizukuHealNotice.COULD_NOT_RESTART,
            ShizukuHealPolicy.notice(ShizukuHeal.START, verifiedRunning = false, underDialog = true),
        )
    }

    @Test
    fun `the dialog does silence what it already said`() {
        // Nothing was attempted during a recording, so there is nothing to add to a warning the user
        // read seconds ago — and a notification that repeats a dialog teaches them to swipe ours away.
        assertEquals(
            ShizukuHealNotice.NONE,
            ShizukuHealPolicy.notice(ShizukuHeal.TELL_ONLY_RECORDING, verifiedRunning = false, underDialog = true),
        )
        assertEquals(
            ShizukuHealNotice.STOPPED,
            ShizukuHealPolicy.notice(ShizukuHeal.TELL_ONLY_RECORDING, verifiedRunning = false, underDialog = false),
        )
    }

    @Test
    fun `nothing is ever posted when no server was running`() {
        // The no-Shizuku phone must be untouched and silent: every combination of the other two inputs.
        listOf(true, false).forEach { verified ->
            listOf(true, false).forEach { dialog ->
                assertEquals(
                    ShizukuHealNotice.NONE,
                    ShizukuHealPolicy.notice(ShizukuHeal.NOTHING, verified, dialog),
                )
            }
        }
    }
}
