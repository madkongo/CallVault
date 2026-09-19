/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.provider.Settings

/**
 * Reads the system Developer options master toggle.
 *
 * Developer options is the hard prerequisite for the whole recording stack: with it OFF, Wireless
 * debugging cannot function and the privileged recorder daemon dies as soon as the app's WD policy
 * turns Wireless debugging off — every "recording" then produces an empty file (observed on
 * OnePlus/Android 16: the daemon binder dies within ~200ms of WD-off after each post-boot launch).
 * Status surfaces must therefore treat "Developer options off" as a broken, not-ready state.
 */
object DeveloperOptions {

    /**
     * True when the Developer options master toggle is enabled. Wrapped in [runCatching]
     * (defaults to false) because the global setting may be absent on some ROMs.
     */
    fun isEnabled(context: Context): Boolean = state(context) == DeveloperOptionsState.ON

    /**
     * Developer options as three states, because Android 17 makes the setting read `0` for every app.
     *
     * Corroborated rather than trusted: both debugging switches live *inside* Developer options, so any
     * of them being reachable proves Developer options are on. See [DeveloperOptionsPolicy], and
     * docs/dev-notes/2026-09-19-android-17-adb-detection-issue-40.md for what believing the raw read cost.
     */
    fun state(context: Context): DeveloperOptionsState = DeveloperOptionsPolicy.of(
        settingSaysOn = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        }.getOrDefault(false),
        usbDebugging = AdbShell.usbDebuggingState(context),
        wirelessDebuggingOn = runCatching { AdbShell.isWirelessDebuggingEnabled(context) }.getOrDefault(false),
        adbd = runCatching { AdbShell.adbdState() }.getOrDefault(AdbdState.UNKNOWN),
    )

    /**
     * True only when Developer options are POSITIVELY known to be off, so hard error states (like the
     * Home status card) never go red on a phone that simply will not say.
     *
     * ⚠️ This used to read the setting with `getString` and rely on `null` meaning "unreadable". Android
     * 17 returns the literal string `"0"` instead, so the guard inverted itself and returned **true on
     * every Android 17 phone** — the opposite of its purpose. It now asks [state], which corroborates.
     */
    fun isExplicitlyDisabled(context: Context): Boolean = state(context) == DeveloperOptionsState.OFF
}
