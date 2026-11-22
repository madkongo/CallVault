/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.os.SystemClock

/**
 * C99: while a "the user switched Wireless debugging off" verdict is still being settled, recovery must not switch it
 * back on. Otherwise the settle window lets the app undo a real user's off before the flag exists. Expires by itself
 * one second after the window, so a lost cancel can never block recovery for long.
 */
class UserOffPending {
    @Volatile
    private var untilMs = 0L

    fun start(nowMs: Long = SystemClock.elapsedRealtime()) {
        untilMs = nowMs + WirelessDebuggingSettledVerdict.SETTLE_WINDOW_MS + EXTRA_MS
    }

    fun clear() {
        untilMs = 0L
    }

    fun isPending(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = nowMs < untilMs

    private companion object {
        const val EXTRA_MS = 1_000L
    }
}
