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
    fun isEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

    /**
     * True only when the setting is POSITIVELY readable as disabled ("0"). Distinct from
     * `!isEnabled()`: an absent or unreadable setting returns false here, so hard error states
     * (like the Home status card) never go red on a ROM that simply doesn't expose the global.
     */
    fun isExplicitlyDisabled(context: Context): Boolean = runCatching {
        Settings.Global.getString(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
        )?.trim()?.toIntOrNull()
    }.getOrNull() == 0
}
