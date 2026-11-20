/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Android says only "crash" about a crash. With debug logging off — every report from someone who did not know to
 * switch it on — the exception was nowhere, so each crash has to leave its own short, always-on note.
 */
class CrashJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun deepThrowable(frames: Int): Throwable {
        val t = IllegalStateException("Bad notification for startForeground")
        t.stackTrace = Array(frames) { StackTraceElement("com.example.C$it", "m$it", "C$it.java", it + 1) }
        return t
    }

    @Test
    fun `an entry names the process, thread, exception and top frames`() {
        val entry = CrashJournal.format(
            atMillis = 0L, pid = 13572, process = "com.baba.callvault", thread = "main",
            error = deepThrowable(3), redact = { it },
        )
        assertTrue(entry, entry.contains("pid=13572"))
        assertTrue(entry.contains("process=com.baba.callvault"))
        assertTrue(entry.contains("thread=main"))
        assertTrue(entry.contains("java.lang.IllegalStateException: Bad notification for startForeground"))
        assertTrue(entry.contains("at com.example.C0.m0(C0.java:1)"))
        assertTrue(entry.contains("at com.example.C2.m2(C2.java:3)"))
    }

    @Test
    fun `only the top frames are kept, and the rest is counted`() {
        val entry = CrashJournal.format(0L, 1, "p", "main", deepThrowable(60), redact = { it })
        assertTrue(entry.contains("C${CrashJournal.MAX_FRAMES - 1}.m"))
        assertFalse(entry.contains("C${CrashJournal.MAX_FRAMES}.m"))
        assertTrue(entry.contains("... ${60 - CrashJournal.MAX_FRAMES} more"))
    }

    @Test
    fun `the cause chain is followed, and a cycle does not loop`() {
        val root = java.io.IOException("disk gone")
        val top = RuntimeException("wrapper", root)
        root.initCause(top) // a cycle, as a buggy library can make
        val entry = CrashJournal.format(0L, 1, "p", "main", top, redact = { it })
        assertTrue(entry.contains("Caused by: java.io.IOException: disk gone"))
        assertTrue(entry.length < CrashJournal.MAX_ENTRY_CHARS + 100)
    }

    @Test
    fun `the whole entry goes through redaction`() {
        val t = IllegalArgumentException("no such file content://x/recording_+972501234567.ogg")
        val entry = CrashJournal.format(0L, 1, "p", "main", t, redact = { it.replace("+972501234567", "[PHONE]") })
        assertFalse(entry.contains("972501234567"))
        assertTrue(entry.contains("[PHONE]"))
    }

    @Test
    fun `an entry is bounded however long the message is`() {
        val entry = CrashJournal.format(0L, 1, "p", "main", RuntimeException("x".repeat(100_000)), redact = { it })
        assertTrue(entry.length <= CrashJournal.MAX_ENTRY_CHARS)
    }

    @Test
    fun `the file keeps the newest entries within its cap`() {
        val file = folder.newFile("crash_journal.txt")
        repeat(200) { i -> CrashJournal.append(file, "entry $i\n" + "y".repeat(1_000)) }
        assertTrue(file.length() <= CrashJournal.MAX_FILE_BYTES)
        val text = file.readText()
        assertTrue(text.contains("entry 199"))
        assertFalse(text.contains("entry 0\n"))
        // Trimmed at an entry boundary, never mid-entry.
        assertTrue(text.startsWith("entry "))
    }

    @Test
    fun `entries for a pid are found for the exit that ended it`() {
        val text = CrashJournal.format(0L, 777, "p", "main", RuntimeException("boom"), redact = { it }) + "\n" +
            CrashJournal.format(0L, 888, "p", "main", RuntimeException("other"), redact = { it })
        assertEquals("java.lang.RuntimeException: boom", CrashJournal.summaryForPid(text, 777))
        assertEquals(null, CrashJournal.summaryForPid(text, 999))
    }

    @Test
    fun `a note that cannot be formatted still records the exception class`() {
        val entry = CrashJournal.safeEntry(0L, 13572, "p", "main", IllegalStateException("x")) { throw OutOfMemoryError("redactor") }
        assertTrue(entry, entry.contains("pid=13572"))
        assertTrue(entry.contains("java.lang.IllegalStateException"))
        assertTrue(entry.contains("OutOfMemoryError"))
    }

    @Test
    fun `trimming replaces the file whole and leaves no temporary file behind`() {
        val file = folder.newFile("crash_journal.txt")
        repeat(100) { i -> CrashJournal.append(file, "entry $i\n" + "y".repeat(1_000)) }
        assertEquals(listOf("crash_journal.txt"), folder.root.list()!!.sorted())
        assertTrue(file.readText().endsWith("\n\n"))
    }
}
