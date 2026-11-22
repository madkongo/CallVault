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
        DriveBackupNotice.onDriveSyncOff(context, "test reset")
        DriveBackupNotice.onDriveReached(context, "test reset")
        nm.cancelAll()
        DriveBackupNotice.clock = { now }
    }

    @org.junit.After
    fun tearDown() { DriveBackupNotice.clock = System::currentTimeMillis }

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

    @Test
    fun `the failed-copy spell counts fruitless runs and any proof that Drive works ends it`() {
        assertEquals(1, DriveBackupNotice.nextFailedRuns(previous = 0, driveProved = false, failures = 2))
        assertEquals(5, DriveBackupNotice.nextFailedRuns(previous = 4, driveProved = false, failures = 1))
        assertEquals("a straggler beside files Drive holds", 0, DriveBackupNotice.nextFailedRuns(9, driveProved = true, failures = 1))
        assertEquals("a run with no failures", 0, DriveBackupNotice.nextFailedRuns(9, driveProved = false, failures = 0))
        val day = DriveBackupNotice.MIN_SPELL_MS
        assertEquals(false, DriveBackupNotice.isCopySpellGivingUp(CloudCopyPolicy.MAX_ATTEMPTS - 1, day))
        assertEquals("one retry chain is not a spell", false, DriveBackupNotice.isCopySpellGivingUp(CloudCopyPolicy.MAX_ATTEMPTS, day - 1))
        assertTrue(DriveBackupNotice.isCopySpellGivingUp(CloudCopyPolicy.MAX_ATTEMPTS, day))
    }

    @Test
    fun `a notice about failing copies survives a listing and is cleared by a copy`() {
        failSpell()
        assertEquals(1, shown())
        val text = shadowOf(shadowOf(nm).allNotifications.single()).contentText.toString()
        assertTrue(text, text.contains("Google Drive is not accepting copies"))

        DriveBackupNotice.onDriveReached(context, "listing ok", byListing = true)
        assertEquals("a listing must not clear it", 1, shown())

        DriveBackupNotice.onDriveReached(context, "copy ok")
        assertEquals(0, shown())
    }

    private var now = 1_000_000L
    private fun failSpell() = repeat(CloudCopyPolicy.MAX_ATTEMPTS) {
        DriveBackupNotice.onCopyRunEnd(context, driveProved = false, tried = 3, failures = 3)
        now += DriveBackupNotice.MIN_SPELL_MS / 5
    }

    @Test
    fun `ten fruitless runs inside one day do not tell the user`() {
        repeat(CloudCopyPolicy.MAX_ATTEMPTS + 3) { DriveBackupNotice.onCopyRunEnd(context, driveProved = false, tried = 1, failures = 1) }
        assertEquals(0, shown())
    }

    @Test
    fun `a notice about listing is still cleared by a listing`() {
        DriveBackupNotice.onGaveUp(context, DriveBackupNotice.Reason.NO_ANSWER, "no answer")
        DriveBackupNotice.onDriveReached(context, "listing ok", byListing = true)
        assertEquals(0, shown())
    }

    @Test
    fun `a copy in the middle of a spell restarts the count`() {
        repeat(CloudCopyPolicy.MAX_ATTEMPTS - 1) { DriveBackupNotice.onCopyRunEnd(context, driveProved = false, tried = 1, failures = 1) }
        DriveBackupNotice.onDriveReached(context, "copy ok")
        DriveBackupNotice.onCopyRunEnd(context, driveProved = false, tried = 1, failures = 1)
        assertEquals(0, shown())
    }
}
