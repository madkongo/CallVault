/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.system.storage.DriveBackupNotice.Action
import com.baba.callvault.system.storage.DriveBackupNotice.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Review C11 follow-up: once the sweep has spent its last attempt without listing the Drive folder, the user
 * must be told that Drive backup is not working; once, and cleared when Drive is reached again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveBackupNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private fun shown() = shadowOf(nm).allNotifications.size

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DriveBackupNotice.onDriveReached(context, "test reset")
        nm.cancelAll()
    }

    @Test
    fun `giving up posts once, reaching Drive clears, nothing else does anything`() {
        assertEquals(Action.POST, DriveBackupNotice.decide(isPosted = false, Event.GAVE_UP))
        assertEquals(Action.NOTHING, DriveBackupNotice.decide(isPosted = true, Event.GAVE_UP))
        assertEquals(Action.CLEAR, DriveBackupNotice.decide(isPosted = true, Event.REACHED))
        assertEquals(Action.NOTHING, DriveBackupNotice.decide(isPosted = false, Event.REACHED))
    }

    @Test
    fun `the notice is posted on the last failed attempt, not again, and cleared when Drive answers`() {
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "the :drive process gave no answer")
        assertEquals(1, shown())
        val text = shadowOf(shadowOf(nm).allNotifications.single()).contentText.toString()
        assertTrue(text, text.contains("Google Drive is not answering"))

        nm.cancelAll() // the user swipes it away
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "again")
        assertEquals("posted once, not on every failed run", 0, shown())

        DriveBackupNotice.onDriveReached(context, "listing ok")
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.FOLDER_UNREADABLE, "folder not usable")
        assertEquals("a new failure after a success is told again", 1, shown())

        DriveBackupNotice.onDriveReached(context, "copy ok")
        assertNull(shadowOf(nm).allNotifications.firstOrNull())
    }

    @Test
    fun `a notice that could not be shown is not counted as posted`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "no permission yet")
        assertEquals(0, shown())

        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "permission granted since")
        assertEquals("the next give-up shows it, because nothing was shown before", 1, shown())
    }

    @Test
    fun `only the attempt that gives up tells the user`() {
        assertTrue(DriveBackupNotice.isGivingUp(CloudCopyPolicy.LIST_FAILURE_MAX_ATTEMPTS - 1))
        assertEquals(false, DriveBackupNotice.isGivingUp(0))
    }

    @Test
    fun `turning Drive sync off clears a posted notice and lets a later failure post again`() {
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "no answer")
        assertEquals(1, shown())

        DriveBackupNotice.onDriveSyncOff(context, "storage switched to device-only")

        assertEquals(0, shown())
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "after re-enabling")
        assertEquals("the posted flag was reset", 1, shown())
    }

    @Test
    fun `turning Drive sync off with nothing posted does nothing`() {
        DriveBackupNotice.onDriveSyncOff(context, "storage switched to device-only")
        assertEquals(0, shown())
    }
}
