/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review N8: a vivo phone started 23 app-call recordings and saved none, and the always-on log showed each start
 * and no outcome. Every app call now ends in exactly one always-on outcome line, with its reason.
 */
class VoipOutcomeTest {

    @Test
    fun `every reason has its own line, and none carries a name`() {
        val lines = VoipOutcome.Reason.entries.map { VoipOutcome.line(it, recordedMs = 1_234L, sizeBytes = 5_678L) }
        assertEquals(lines.size, lines.toSet().size)
        lines.forEach { line ->
            assertTrue(line, line.startsWith("app call outcome: "))
            assertTrue(line, line.contains("recorded=1234 ms"))
            assertTrue(line, line.contains("size=5678 bytes"))
        }
    }

    @Test
    fun `the outcome says plainly whether the call was kept`() {
        assertTrue(VoipOutcome.line(VoipOutcome.Reason.SAVED, null, 10L).startsWith("app call outcome: SAVED"))
        assertTrue(VoipOutcome.line(VoipOutcome.Reason.UNANSWERED, null, null).startsWith("app call outcome: DISCARDED"))
        assertTrue(VoipOutcome.line(VoipOutcome.Reason.NO_AUDIO, null, null).startsWith("app call outcome: FAILED"))
        assertTrue(VoipOutcome.line(VoipOutcome.Reason.RECORDER_REFUSED, null, null).startsWith("app call outcome: NOT STARTED"))
    }

    @Test
    fun `a long call's length and size survive the report's phone-number redaction`() {
        val line = VoipOutcome.line(VoipOutcome.Reason.SAVED, recordedMs = 3_061_000L, sizeBytes = 11_700_000L)
        assertEquals(line, com.baba.callvault.utils.LogRedaction.redact(line, "salt", false))
    }

    @Test
    fun `an unknown length or size is said as unknown, not zero`() {
        val line = VoipOutcome.line(VoipOutcome.Reason.NO_AUDIO, recordedMs = null, sizeBytes = null)
        assertTrue(line, line.contains("recorded=?"))
        assertTrue(line, line.contains("size=?"))
    }

    @Test
    fun `every discard of a started app call recording goes through the logged discard`() {
        // Guard: a staged recording deleted anywhere else in the coordinator is a discard with no outcome line.
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val source = File(root, "com/baba/callvault/services/recording/VoipRecordingCoordinator.kt").readText()
        val deletes = Regex("""stagingFile\?\.delete\(\)""").findAll(source).count()
        assertEquals("staged-file deletes outside discardStarted()", 1, deletes)
        assertFalse(source.contains("discardAsIgnored = true"))
    }
}
