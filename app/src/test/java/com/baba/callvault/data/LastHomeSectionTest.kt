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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stored half of "reopen the section you were in".
 *
 * [HomeSection.opening] is pinned on its own; what is checked here is that what goes into
 * SharedPreferences is what comes back out of it, and that an install which has never stored one
 * reads as never — not as an empty string, and not as a section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastHomeSectionTest {

    private val preferences =
        AppPreferences(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `a fresh install has never been anywhere`() {
        assertNull(preferences.getLastHomeSectionKey())
    }

    @Test
    fun `the section the user was in survives the visit`() {
        preferences.setLastHomeSection(HomeSection.Transcripts)

        assertEquals(HomeSection.Transcripts.key, preferences.getLastHomeSectionKey())
        assertEquals(
            HomeSection.Transcripts,
            HomeSection.opening(AppScreen.Home, preferences.getLastHomeSectionKey())
        )
    }
}
