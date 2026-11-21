/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.viewmodels.SettingsViewModel
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C143: the chosen app language must be known without an activity on Android 11-12. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalizedContextMirrorTest {

    private val he = Locale.forLanguageTag("he")
    private val zh = Locale.forLanguageTag("zh-CN")

    @Test
    fun `an activity-loaded locale wins over everything`() {
        assertEquals(he, LocalizedContext.resolve(compat = he, system = zh, storedTag = "fr", systemKnowsLocale = true))
    }

    @Test
    fun `below Android 13 the mirrored tag is used when no activity has loaded the locale`() {
        assertEquals(zh, LocalizedContext.resolve(compat = null, system = null, storedTag = "zh-CN", systemKnowsLocale = false))
    }

    @Test
    fun `on Android 13 and up the system is the truth, so a stale mirror never overrides it`() {
        // The user switched the app back to "system default" in Android's own settings: the mirror is stale.
        assertNull(LocalizedContext.resolve(compat = null, system = null, storedTag = "zh-CN", systemKnowsLocale = true))
        assertEquals(he, LocalizedContext.resolve(compat = null, system = he, storedTag = "zh-CN", systemKnowsLocale = true))
    }

    @Test
    fun `no tag and no locale means follow the phone`() {
        assertNull(LocalizedContext.resolve(compat = null, system = null, storedTag = null, systemKnowsLocale = false))
        assertNull(LocalizedContext.resolve(compat = null, system = null, storedTag = "", systemKnowsLocale = false))
    }

    @Test
    fun `choosing a language in Settings stores its tag, and following the system clears it`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SettingsViewModel(app)
        vm.setAppLanguage("zh-CN")
        assertEquals("zh-CN", AppPreferences(app).getAppLanguageTag())
        vm.setAppLanguage("")
        assertNull(AppPreferences(app).getAppLanguageTag())
    }
}
