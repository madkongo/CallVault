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
 * What CallVault owes a Shizuku server it is about to kill by restarting adbd (#39).
 *
 * Measured on the OP9 on 2026-09-18: arming off-Wi-Fi recording gave adbd a new pid and the running
 * Shizuku server was gone and never came back. Shizuku is not CallVault's, so the cost has to be stated.
 */
class ShizukuChurnPolicyTest {

    @Test
    fun `nothing to say when no Shizuku server is running`() {
        assertEquals(
            ShizukuChurnAdvice.NOTHING,
            ShizukuChurnPolicy.decide(shizukuServerRunning = false, underDialog = false),
        )
        assertEquals(
            ShizukuChurnAdvice.NOTHING,
            ShizukuChurnPolicy.decide(shizukuServerRunning = false, underDialog = true),
        )
    }

    @Test
    fun `a dialog carries the warning before the user commits`() {
        // Turning off-Wi-Fi recording on is deliberate and one-time; the dialog is the last moment the
        // user can still say no, so a notification afterwards would only repeat what they already chose.
        assertEquals(
            ShizukuChurnAdvice.WARN_FIRST,
            ShizukuChurnPolicy.decide(shizukuServerRunning = true, underDialog = true),
        )
    }

    @Test
    fun `a restart with no dialog is explained afterwards`() {
        // The re-arm the launcher does by itself after a reboot, and the Default USB Configuration
        // picker: the user never sees a warning, so they get told which app it stopped.
        assertEquals(
            ShizukuChurnAdvice.TELL_AFTER,
            ShizukuChurnPolicy.decide(shizukuServerRunning = true, underDialog = false),
        )
    }

    @Test
    fun `it never answers with anything that cancels the restart`() {
        // Recording wins: a missed call cannot be recovered and a Shizuku server takes two taps to
        // start again. If a "SKIP" ever appears here, that trade has been quietly reversed.
        val everyAnswer = listOf(true, false).flatMap { running ->
            listOf(true, false).map { dialog -> ShizukuChurnPolicy.decide(running, dialog) }
        }
        assertEquals(
            listOf(
                ShizukuChurnAdvice.WARN_FIRST,
                ShizukuChurnAdvice.TELL_AFTER,
                ShizukuChurnAdvice.NOTHING,
                ShizukuChurnAdvice.NOTHING,
            ),
            everyAnswer,
        )
    }
}
