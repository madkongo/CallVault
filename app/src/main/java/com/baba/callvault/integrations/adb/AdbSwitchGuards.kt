/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Whether the user-facing off-Wi-Fi toggle may restart adbd now (C35).
 *
 * Arming and disarming the loopback listener both restart adbd, and a restart kills the daemon that is
 * recording the call. Only the Settings toggle asks through here; the recorder launcher re-arms on its own path
 * and must never be refused, because a call that needs the recorder is exactly when it does.
 */
object OfflineToggleGuard {
    fun mayRestartAdbd(recordingLive: Boolean): Boolean = !recordingLive

    /**
     * What the stored opt-in should be after an enable attempt (C56). The opt-in is set BEFORE arming, because
     * arming borrows Wireless debugging only while it is on ([LoopbackBorrowPolicy]); so a failed attempt has to
     * put it back. An opt-in that was already on is left alone: this attempt did not set it.
     */
    fun optInAfterEnable(wasOn: Boolean, result: LoopbackArm): Boolean = wasOn || result == LoopbackArm.ARMED
}

/** The "user turned Wireless debugging off" flag, reconciled with what the switch reads now (C99). */
object StaleWdOffFlag {
    /**
     * The flag says the switch is off; the switch reads on. Someone turned it on again, which is the keep-alive
     * observer's own "on again, by anyone" rule, but that observer only runs in standalone mode and only sees
     * changes made while it watches. A flag left behind makes the enable gate answer RESPECT_USER and leaves adbd
     * stopped after a revival cycle (C99). Only ever clears; never writes the switch.
     */
    fun shouldClear(flagSet: Boolean, wirelessDebuggingReadsOn: Boolean): Boolean = flagSet && wirelessDebuggingReadsOn
}
