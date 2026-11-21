/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A settings file from another build must never be able to crash this one (C84). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPreferencesImportSafetyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun file(vararg entries: String) =
        """{"app":"CallVault","format":1,"settings":{${entries.joinToString(",")}}}"""

    private fun str(key: String, value: String) = """"$key":{"type":"string","value":"$value"}"""

    @Test
    fun `an unknown theme value in the file is not applied and the app still starts`() {
        val prefs = AppPreferences(context)
        prefs.setThemeMode(AppPreferences.ThemeMode.DARK)
        val applied = prefs.importFromJson(file(str("theme_mode", "sepia")))
        assertEquals(0, applied)
        assertEquals(AppPreferences.ThemeMode.DARK, prefs.getThemeMode())
    }

    @Test
    fun `an unknown ignore-contacts value in the file is not applied`() {
        val prefs = AppPreferences(context)
        val applied = prefs.importFromJson(
            file(str("ignore_contacts_mode_incoming", "block_all"), str("ignore_contacts_mode_outgoing", "all")),
        )
        assertEquals(1, applied)
        assertEquals(AppPreferences.IgnoreContactsMode.NONE, prefs.getIgnoreContactsModeIncoming())
        assertEquals(AppPreferences.IgnoreContactsMode.ALL, prefs.getIgnoreContactsModeOutgoing())
    }

    @Test
    fun `a stored unknown theme or ignore mode falls back to the default instead of throwing`() {
        val raw = context.getSharedPreferences("callvault_prefs", Context.MODE_PRIVATE)
        raw.edit().putString("theme_mode", "sepia")
            .putString("ignore_contacts_mode_incoming", "block_all")
            .putString("ignore_contacts_mode_outgoing", "block_all").commit()
        val prefs = AppPreferences(context)
        assertEquals(AppPreferences.ThemeMode.SYSTEM, prefs.getThemeMode())
        assertEquals(AppPreferences.IgnoreContactsMode.NONE, prefs.getIgnoreContactsModeIncoming())
        assertEquals(AppPreferences.IgnoreContactsMode.NONE, prefs.getIgnoreContactsModeOutgoing())
    }

    @Test
    fun `an entry whose type differs from the stored value is not applied`() {
        val prefs = AppPreferences(context)
        prefs.setShowToastsEnabled(true)
        val applied = prefs.importFromJson(file(str("show_toasts", "yes")))
        assertEquals(0, applied)
        assertEquals(true, prefs.isShowToastsEnabled())
    }
}
