/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When CallVault may borrow the user's Wireless debugging switch to re-arm the off-Wi-Fi listener.
 *
 * The case that made this necessary is the reboot deadlock of 2026-09-19: a persisted "the user turned it
 * off" flag, a listener that every reboot clears, and a refusal whose only escape was the thing it
 * refused. See docs/dev-notes/2026-09-19-reboot-deadlock-wd-off-by-user.md.
 */
class LoopbackBorrowPolicyTest {

    @Test
    fun `the state right after a reboot is exactly when borrowing is allowed`() {
        // Off-Wi-Fi recording on, USB debugging on, and the listener gone because the phone restarted.
        assertTrue(
            LoopbackBorrowPolicy.mayBorrow(offlineRecordingOn = true, usbDebuggingOn = true, loopbackArmed = false),
        )
    }

    @Test
    fun `an armed listener means there is another way in, so the user's switch is left alone`() {
        // The 2026-09-14 regression guard: CallVault used to switch the OP9's Wireless debugging back on
        // 50 ms after the user's tap. With a listener already armed nothing needs recovering.
        assertFalse(
            LoopbackBorrowPolicy.mayBorrow(offlineRecordingOn = true, usbDebuggingOn = true, loopbackArmed = true),
        )
    }

    @Test
    fun `without off-Wi-Fi recording there is nothing to re-arm, so nothing to borrow for`() {
        assertFalse(
            LoopbackBorrowPolicy.mayBorrow(offlineRecordingOn = false, usbDebuggingOn = true, loopbackArmed = false),
        )
    }

    @Test
    fun `without USB debugging the listener would die with the switch we borrowed`() {
        // adbd stops when its last transport goes, and the listener lives inside adbd (R7).
        assertFalse(
            LoopbackBorrowPolicy.mayBorrow(offlineRecordingOn = true, usbDebuggingOn = false, loopbackArmed = false),
        )
    }
}
