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
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class DeveloperOptionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun disabled_when_setting_is_absent() {
        assertFalse(DeveloperOptions.isEnabled(context))
    }

    @Test
    fun disabled_when_setting_is_zero() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
        assertFalse(DeveloperOptions.isEnabled(context))
    }

    @Test
    fun enabled_when_setting_is_one() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        assertTrue(DeveloperOptions.isEnabled(context))
    }

    @Test
    fun absent_setting_is_not_explicitly_disabled() {
        // An unreadable/absent global must NOT count as disabled — hard error states rely on this.
        assertFalse(DeveloperOptions.isExplicitlyDisabled(context))
    }

    @Test
    fun zero_setting_is_explicitly_disabled() {
        // putString mirrors real framework storage (Settings.Global.putInt writes the string form).
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0")
        assertTrue(DeveloperOptions.isExplicitlyDisabled(context))
    }

    @Test
    fun one_setting_is_not_explicitly_disabled() {
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1")
        assertFalse(DeveloperOptions.isExplicitlyDisabled(context))
    }
}
