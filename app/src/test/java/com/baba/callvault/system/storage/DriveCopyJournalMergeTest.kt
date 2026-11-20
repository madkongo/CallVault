/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C145: the app and the :drive copier each write their own journal file (one writing process per file, so a trim
 * can never drop the other process's line); the report merges the two by their timestamp prefix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveCopyJournalMergeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `merge interleaves the two files by timestamp`() {
        val app = "2026-11-20 10:00:00.000 app one\n2026-11-20 10:00:02.000 app two\n"
        val copier = "2026-11-20 10:00:01.000 [:drive] copier one\n2026-11-20 10:00:03.000 [:drive] copier two\n"

        val merged = DriveCopyJournal.merge(app, copier)

        assertEquals(
            "2026-11-20 10:00:00.000 app one\n2026-11-20 10:00:01.000 [:drive] copier one\n" +
                "2026-11-20 10:00:02.000 app two\n2026-11-20 10:00:03.000 [:drive] copier two\n",
            merged,
        )
    }

    @Test
    fun `a line without a timestamp stays with the line before it`() {
        val app = "2026-11-20 10:00:00.000 app one\ncontinued\n2026-11-20 10:00:05.000 app two\n"
        val copier = "2026-11-20 10:00:01.000 [:drive] copier\n"

        val merged = DriveCopyJournal.merge(app, copier)

        assertEquals(
            "2026-11-20 10:00:00.000 app one\ncontinued\n2026-11-20 10:00:01.000 [:drive] copier\n2026-11-20 10:00:05.000 app two\n",
            merged,
        )
    }

    @Test
    fun `one file missing or empty gives the other unchanged, both missing gives null`() {
        val only = "2026-11-20 10:00:00.000 only\n"
        assertEquals(only, DriveCopyJournal.merge(only, null))
        assertEquals(only, DriveCopyJournal.merge(null, only))
        assertEquals(only, DriveCopyJournal.merge("", only))
        assertNull(DriveCopyJournal.merge(null, null))
        assertNull(DriveCopyJournal.merge("", ""))
    }

    @Test
    fun `the copier writes its own file and the app never touches it`() {
        File(context.filesDir, "drive_copy_journal.txt").delete()
        File(context.filesDir, "drive_copy_journal_copier.txt").delete()

        DriveCopyJournal.record(context, "from the app")
        DriveCopyJournal.record(context, "from the copier", fromCopier = true)

        val appFile = File(context.filesDir, "drive_copy_journal.txt").readText()
        val copierFile = File(context.filesDir, "drive_copy_journal_copier.txt").readText()
        assertTrue(appFile, appFile.contains("from the app") && !appFile.contains("from the copier"))
        assertTrue(copierFile, copierFile.contains("[:drive] from the copier") && !copierFile.contains("from the app"))
        val report = DriveCopyJournal.readForReport(context)!!
        assertTrue(report, report.indexOf("from the app") in 0 until report.indexOf("from the copier"))
    }

    @Test
    fun `a trim in the app can never drop a copier line`() {
        File(context.filesDir, "drive_copy_journal.txt").delete()
        File(context.filesDir, "drive_copy_journal_copier.txt").delete()
        DriveCopyJournal.record(context, "copier survives", fromCopier = true)

        val big = "x".repeat(2_000)
        repeat(DriveCopyJournal.MAX_BYTES_PER_FILE / 2_000 + 20) { DriveCopyJournal.record(context, big) }

        assertTrue(File(context.filesDir, "drive_copy_journal.txt").length() <= DriveCopyJournal.MAX_BYTES_PER_FILE)
        assertTrue(DriveCopyJournal.readForReport(context)!!.contains("copier survives"))
    }
}
