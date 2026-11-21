/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.ui.navigation.NotificationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review C150: the update notice must reach the live window, not stack a second one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateNotificationsIntentTest {
    @Test
    fun tap_is_delivered_to_the_running_window_and_carries_the_destination() {
        val intent = UpdateNotifications.openAppIntent(ApplicationProvider.getApplicationContext<Application>())

        val wanted = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(wanted, intent.flags and wanted)
        assertEquals(NotificationDestination.Update.key, intent.getStringExtra(NotificationDestination.EXTRA))
        assertTrue(intent.component?.className?.endsWith("MainActivity") == true)
    }
}
