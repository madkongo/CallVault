/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Review C22: the schedule signature must not depend on the phone's language. Persian, Bengali and Arabic
 * format numbers with their own digits, so a signature written in one language never matched one read in
 * another, and every app start cancelled and re-enqueued the sweep.
 */
class SyncSchedulerSignatureLocaleTest {

    private val saved = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(saved)

    private fun sig() = SyncScheduler.signature(StorageTarget.BOTH, SyncScheduleMode.DAILY, 15, 45, 1)

    @Test
    fun `the signature uses Latin digits in a Persian, Bengali or Arabic locale`() {
        for (tag in listOf("fa", "bn", "ar-EG")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            val s = sig()
            assertTrue("$tag gave '$s'", s.contains("15:45"))
            assertTrue("$tag gave '$s'", s.all { !it.isDigit() || it in '0'..'9' })
        }
    }

    @Test
    fun `a signature stored with native digits before the update is the same schedule`() {
        Locale.setDefault(Locale.ROOT)
        val ascii = sig()
        val persian = ascii.replace("15:45", "۱۵:۴۵")
        val bengali = ascii.replace("15:45", "১৫:৪৫")
        for (old in listOf(persian, bengali)) {
            val d = SyncScheduler.decide(old, ascii, listOf(WorkInfo.State.ENQUEUED))
            assertEquals(ExistingPeriodicWorkPolicy.KEEP, d.policy)
            assertEquals(SyncScheduler.Reason.UNCHANGED_ALIVE, d.reason)
        }
    }

    @Test
    fun `a real change is still a change after the digits are normalised`() {
        Locale.setDefault(Locale.ROOT)
        val old = sig().replace("15:45", "۱۵:۴۶")
        assertEquals(SyncScheduler.Reason.CHANGED, SyncScheduler.decide(old, sig(), listOf(WorkInfo.State.ENQUEUED)).reason)
    }
}
