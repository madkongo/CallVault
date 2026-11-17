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
import com.baba.callvault.ui.navigation.AppScreen
import com.baba.callvault.ui.navigation.HomeSection
import com.baba.callvault.ui.navigation.NotificationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BabApps item 133: the start-page setting. The app opens on the page chosen in Settings (the hub
 * by default); this pins that what is stored is what [HomeSection.opening] opens, which is what
 * AppNavigationScreen does with [AppPreferences.getStartPageKey].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartPageTest {

    private val preferences = AppPreferences(ApplicationProvider.getApplicationContext<Context>())

    private fun opensOn(): HomeSection? = HomeSection.opening(AppScreen.Home, preferences.getStartPageKey())

    @Test
    fun `the default start page is the hub`() {
        assertEquals(HomeSection.Hub.key, preferences.getStartPageKey())
        assertEquals(HomeSection.Hub, opensOn())
    }

    @Test
    fun `each choice opens its own section`() {
        for (section in HomeSection.entries) {
            preferences.setStartPage(section)
            assertEquals(section, opensOn())
        }
    }

    @Test
    fun `the last section used no longer decides where the app opens`() {
        preferences.setStartPage(HomeSection.Recordings)
        preferences.setLastHomeSection(HomeSection.Summaries)
        assertEquals(HomeSection.Recordings, opensOn())
    }

    @Test
    fun `an unknown stored start page opens the hub`() {
        preferences.importFromJson("""{"settings":{"start_page":{"type":"string","value":"speakers"}}}""")
        assertEquals(HomeSection.Hub, opensOn())
    }

    @Test
    fun `onboarding still wins over the start page`() {
        preferences.setStartPage(HomeSection.Summaries)
        assertNull(HomeSection.opening(AppScreen.Wizard, preferences.getStartPageKey()))
    }

    @Test
    fun `a notification or share-import destination beats the start page`() {
        preferences.setStartPage(HomeSection.Recordings)
        assertEquals(
            HomeSection.Transcripts,
            HomeSection.forNotification(AppScreen.Home, NotificationDestination.Transcript),
        )
        assertEquals(
            HomeSection.Hub,
            HomeSection.forNotification(AppScreen.Home, NotificationDestination.Status),
        )
    }

    @Test
    fun `the start page travels with an exported settings file`() {
        preferences.setStartPage(HomeSection.Transcripts)
        assertTrue(preferences.exportToJson().contains("\"start_page\""))
    }
}
