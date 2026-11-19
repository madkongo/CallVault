/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.VoipMissPolicy.NotReadyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Review N18 (OP9, rf4, 2026-11-19): CallVault was killed 15 s into an answered WhatsApp call and restarted a second
 * later. The restarted detector saw the call already up and started it while the recorder was not connected yet, and
 * the app posted "An app call was not recorded" at once. False: the recorder kept recording the call for the killed
 * process, and the rescue published all 43 s. A not-connected start is now judged at the call's END, by whether a
 * recording of the call exists anywhere.
 */
class VoipNotReadyMissTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `restarted into a call the recorder is still recording for the killed app - no miss`() {
        assertEquals(
            NotReadyVerdict.RECORDED_ELSEWHERE,
            VoipMissPolicy.notReadyAtCallEnd(lookedLikeCallAtStart = true, recorderRecordingUnowned = true, stagedDuringCall = false),
        )
    }

    @Test
    fun `the orphan was already stopped and its file written during the call - no miss`() {
        assertEquals(
            NotReadyVerdict.RECORDED_ELSEWHERE,
            VoipMissPolicy.notReadyAtCallEnd(lookedLikeCallAtStart = true, recorderRecordingUnowned = false, stagedDuringCall = true),
        )
    }

    @Test
    fun `the recorder was down the whole call and nothing recorded it - the miss is reported`() {
        assertEquals(
            NotReadyVerdict.REPORT,
            VoipMissPolicy.notReadyAtCallEnd(lookedLikeCallAtStart = true, recorderRecordingUnowned = null, stagedDuringCall = false),
        )
        assertEquals(
            NotReadyVerdict.REPORT,
            VoipMissPolicy.notReadyAtCallEnd(lookedLikeCallAtStart = true, recorderRecordingUnowned = false, stagedDuringCall = false),
        )
    }

    @Test
    fun `nothing looked like a call at the start - no miss (issue 29)`() {
        assertEquals(
            NotReadyVerdict.NOT_A_CALL,
            VoipMissPolicy.notReadyAtCallEnd(lookedLikeCallAtStart = false, recorderRecordingUnowned = null, stagedDuringCall = false),
        )
    }

    @Test
    fun `only a staged recording written during the call counts`() {
        val since = 1_000_000_000_000L
        val during = File(tmp.root, "rec_stage_1.tmp").apply { writeText("audio"); setLastModified(since + 5_000) }
        val before = File(tmp.root, "rec_stage_2.tmp").apply { writeText("audio"); setLastModified(since - 60_000) }
        val note = File(tmp.root, "rec_stage_3.tmp.note").apply { writeText("v=1"); setLastModified(since + 5_000) }
        val empty = File(tmp.root, "rec_stage_4.tmp").apply { writeText(""); setLastModified(since + 5_000) }

        assertTrue(VoipMissPolicy.stagedDuringCall(listOf(before, during), since) { false })
        assertFalse(VoipMissPolicy.stagedDuringCall(listOf(before, note, empty), since) { false })
        // One a live recording in this process owns (a phone call's) is not this app call's recording.
        assertFalse(VoipMissPolicy.stagedDuringCall(listOf(during), since) { it == during })
    }

    @Test
    fun `a start with no recorder never reports the miss itself`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val source = File(root, "com/baba/callvault/services/recording/VoipRecordingCoordinator.kt").readText()
        val branch = source.substringAfter("if (service == null) {").substringBefore("return\n")
        assertFalse(branch, branch.contains("reportMissed"))
        assertTrue(branch, branch.contains("notReadyAtStart"))
    }
}
