/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.services.recording.VoipMissPolicy.NotReadyVerdict
import com.baba.callvault.system.storage.StagingNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** Test clock: the restart lands at T, the call ends at T + 30 s. */
    private val t = 1_800_000_000_000L

    private class FakeEnv(var nowMs: Long) : VoipNotReadyMiss.Env {
        var looksLikeACall = true
        var liveCallBeganAt: Long? = null
        var recorderRecordingUnowned: Boolean? = null
        var staged: List<File> = emptyList()
        var live: Set<File> = emptySet()
        var catalogued: List<Pair<String, Long?>>? = emptyList()
        val lines = mutableListOf<String>()
        var reports = 0

        override fun now(): Long = nowMs
        override fun looksLikeACall(): Boolean = looksLikeACall
        override fun liveCallBeganAt(): Long? = liveCallBeganAt
        override fun recorderRecordingUnowned(): Boolean? = recorderRecordingUnowned
        override fun stagedFiles(): List<File> = staged
        override fun isLiveStaging(file: File): Boolean = file in live
        override fun catalogued(): List<Pair<String, Long?>>? = catalogued
        override fun record(line: String) { lines += line }
        override fun reportMiss() { reports++ }
    }

    /** A not-ready start of [call] at [env]'s now, then its end at [endAt], then the settle: the coordinator's wiring. */
    private fun startEndSettle(miss: VoipNotReadyMiss, env: FakeEnv, call: Int, endAt: Long): NotReadyVerdict? {
        miss.onNotReadyStart(call, env)
        env.nowMs = endAt
        val ended = miss.takeAtEnd(call, env) ?: return null
        return miss.settle(ended, env)
    }

    private fun stagedFile(name: String, startedAt: Long, modifiedAt: Long): File {
        val f = File(tmp.root, name).apply { writeText("audio"); setLastModified(modifiedAt) }
        StagingNote.fileFor(f).writeText(StagingNote("content://folder", "x.ogg", "audio/ogg", startedAt).encode())
        return f
    }

    @Test
    fun `restarted into a call the recorder is still recording for the killed app - no miss`() {
        val env = FakeEnv(t).apply { recorderRecordingUnowned = true }
        assertEquals(NotReadyVerdict.RECORDED_ELSEWHERE, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
        assertEquals(0, env.reports)
    }

    @Test
    fun `the recorder was down the whole call and nothing recorded it - the miss is reported once`() {
        val env = FakeEnv(t)
        assertEquals(NotReadyVerdict.REPORT, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
        assertEquals(1, env.reports)
        assertTrue(env.lines.toString(), env.lines.any { it.contains("-> REPORT") })
    }

    @Test
    fun `the killed app's recording, stopped and still staged, is found and named in the log`() {
        // It started before the restart (the call was already 15 s in) and was written until the call ended.
        val orphan = stagedFile("rec_stage_9.tmp", startedAt = t - 15_000, modifiedAt = t + 29_000)
        val env = FakeEnv(t).apply { staged = listOf(orphan); recorderRecordingUnowned = false }
        assertEquals(NotReadyVerdict.RECORDED_ELSEWHERE, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
        assertTrue(env.lines.toString(), env.lines.any { it.contains("rec_stage_9.tmp") })
    }

    @Test
    fun `a phone call's recording started after the app call ended is not taken for it`() {
        // endInBackground: the phone call's own staged file appears before the off-thread settle reads the folder.
        val phoneCall = stagedFile("rec_stage_10.tmp", startedAt = t + 30_500, modifiedAt = t + 31_000)
        val env = FakeEnv(t).apply { staged = listOf(phoneCall) }
        assertEquals(NotReadyVerdict.REPORT, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
    }

    @Test
    fun `the killed app's recording, already rescued into the library, counts and is named in the log`() {
        // The rescue published the staged file under its note's name and deleted it before the call ended.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", java.util.Locale.CANADA).format(java.util.Date(t - 15_000))
        val name = "${stamp}_voip-WhatsApp.ogg"
        val start = RecordingsRepository.parseName(name).startedAtMillis
        assertNotNull("a rescued app call's name carries a readable start", start)
        val env = FakeEnv(t).apply {
            liveCallBeganAt = t - 16_000
            catalogued = listOf("20200101_000000.000+0000_voip.ogg" to 1_577_836_800_000L, name to start)
        }
        assertEquals(NotReadyVerdict.RECORDED_ELSEWHERE, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
        assertTrue(env.lines.toString(), env.lines.any { it.contains(name) })
    }

    @Test
    fun `an earlier recording in the library does not hide a real miss`() {
        val env = FakeEnv(t).apply {
            liveCallBeganAt = t - 16_000
            catalogued = listOf("old.ogg" to t - 3_600_000)
        }
        assertEquals(NotReadyVerdict.REPORT, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
    }

    @Test
    fun `an unreadable library is not taken as proof of a loss`() {
        val env = FakeEnv(t).apply { catalogued = null }
        assertEquals(NotReadyVerdict.UNVERIFIABLE, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
        assertEquals(0, env.reports)
    }

    @Test
    fun `a stale not-ready start of an earlier call never decides the next one`() {
        val miss = VoipNotReadyMiss()
        // Call 0: nothing looked like a call, and its end never came.
        val env = FakeEnv(t - 600_000).apply { looksLikeACall = false }
        miss.onNotReadyStart(0, env)
        // Call 1: a real call with the recorder down. Its own evidence and start time decide.
        env.nowMs = t
        env.looksLikeACall = true
        assertEquals(NotReadyVerdict.REPORT, startEndSettle(miss, env, call = 1, endAt = t + 30_000))
        assertEquals(1, env.reports)
    }

    @Test
    fun `a later start of another call forgets the stale record, and its end settles nothing`() {
        val miss = VoipNotReadyMiss()
        val env = FakeEnv(t)
        miss.onNotReadyStart(0, env)
        miss.forgetOtherCalls(1, env) // call 1 starts and returns early (master switch off, app switched off, ...)
        assertNull(miss.takeAtEnd(1, env))
        assertNull(miss.takeAtEnd(0, env))
        assertEquals(0, env.reports)
    }

    @Test
    fun `a start of the same call that finds the recorder clears the record`() {
        val miss = VoipNotReadyMiss()
        val env = FakeEnv(t)
        miss.onNotReadyStart(0, env)
        miss.clear("a later start of this call found the recorder", env)
        assertNull(miss.takeAtEnd(0, env))
    }

    @Test
    fun `nothing looked like a call at the start - no miss (issue 29)`() {
        val env = FakeEnv(t).apply { looksLikeACall = false }
        assertEquals(NotReadyVerdict.NOT_A_CALL, startEndSettle(VoipNotReadyMiss(), env, call = 0, endAt = t + 30_000))
        assertEquals(0, env.reports)
    }

    @Test
    fun `only a staged recording written during the call counts`() {
        val during = stagedFile("rec_stage_1.tmp", startedAt = t - 5_000, modifiedAt = t + 5_000)
        val before = stagedFile("rec_stage_2.tmp", startedAt = t - 120_000, modifiedAt = t - 60_000)
        val empty = File(tmp.root, "rec_stage_4.tmp").apply { writeText(""); setLastModified(t + 5_000) }
        val note = StagingNote.fileFor(during)
        assertEquals(during, VoipMissPolicy.stagedRecordingOfCall(listOf(before, during), t, t + 30_000) { false })
        assertNull(VoipMissPolicy.stagedRecordingOfCall(listOf(before, note, empty), t, t + 30_000) { false })
        // One a live recording in this process owns is not this app call's recording.
        assertNull(VoipMissPolicy.stagedRecordingOfCall(listOf(during), t, t + 30_000) { it == during })
    }
}
