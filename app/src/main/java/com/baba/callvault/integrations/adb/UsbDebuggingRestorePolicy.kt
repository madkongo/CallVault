/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** What to do when USB debugging has just gone off. */
enum class UsbRestore {
    /** Switch it back on now, without asking. */
    RESTORE,

    /** Leave it, and offer the user a one-tap "Turn USB debugging on" on the notification. */
    ASK,

    /** Not our business: another mode, recording off, or no grant to write it. */
    NONE,
}

/**
 * Decides whether CallVault may switch USB debugging back on by itself.
 *
 * Some phones switch USB debugging off on their own several times a day — Samsung One UI (2026-10-05, S24 FE)
 * and Honor MagicOS (2026-11-04, BVL-N59). Away from Wi-Fi nothing else brings `adbd` back, so every call
 * until the user notices is lost. The Shizuku fork by thedjchi already writes `adb_enabled=1` back with
 * WRITE_SECURE_SETTINGS (its `AdbStartWorker`), which CallVault holds too.
 *
 * Two guards keep this from overriding a person:
 *  - **Only when nobody can have done it by hand.** A screen that is off or locked at the moment of the change
 *    rules out the user flipping it in Developer options. With the screen on and unlocked it may well be them,
 *    so they are asked instead.
 *  - **No tug-of-war.** If the phone keeps switching it off again, CallVault stops after [MAX_RESTORES] in
 *    [WINDOW_MS] and asks, rather than fighting the system in a loop.
 *
 * Pure, so every branch is tested.
 */
object UsbDebuggingRestorePolicy {

    const val MAX_RESTORES = 3
    const val WINDOW_MS = 10 * 60 * 1000L

    /**
     * @param needsAdb the recorder runs over CallVault's own ADB (standalone) and recording is on.
     * @param hasGrant WRITE_SECURE_SETTINGS, without which the write is refused.
     * @param screenOn the display is on at the moment of the change.
     * @param locked the keyguard is showing.
     * @param recentRestores when earlier automatic restores happened.
     */
    fun decide(
        needsAdb: Boolean,
        hasGrant: Boolean,
        screenOn: Boolean,
        locked: Boolean,
        recentRestores: List<Long>,
        now: Long,
    ): UsbRestore = when {
        !needsAdb || !hasGrant -> UsbRestore.NONE
        recentRestores.count { now - it in 0 until WINDOW_MS } >= MAX_RESTORES -> UsbRestore.ASK
        screenOn && !locked -> UsbRestore.ASK
        else -> UsbRestore.RESTORE
    }
}
