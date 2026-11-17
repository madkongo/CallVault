/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BabApps item 131: "Use system colours" is on until the user says otherwise; a stored choice always wins. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DynamicColorDefaultTest {

    private val prefs get() = AppPreferences(ApplicationProvider.getApplicationContext())

    @Test
    fun `system colours are on when nothing is stored`() {
        assertTrue(prefs.isDynamicColorEnabled())
    }

    @Test
    fun `a user who switched it off keeps it off`() {
        prefs.setDynamicColorEnabled(false)
        assertFalse(prefs.isDynamicColorEnabled())
    }

    @Test
    fun `a user who switched it on keeps it on`() {
        prefs.setDynamicColorEnabled(true)
        assertTrue(prefs.isDynamicColorEnabled())
    }
}
