/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepPairedTest {

    private class FakeLimit(var value: Long?, var writable: Boolean = true) : AdbConnectionLimit {
        var writes = mutableListOf<Long>()
        override fun read(): Long? = value
        override fun write(ms: Long): Boolean {
            writes += ms
            if (!writable) return false
            value = ms
            return true
        }
    }

    private class FakeMemory(
        var storedAnswer: KeepPairedAnswer = KeepPairedAnswer.NOT_ASKED,
        var storedOriginal: Long? = null,
    ) : KeepPairedMemory {
        override fun answer() = storedAnswer
        override fun setAnswer(answer: KeepPairedAnswer) { storedAnswer = answer }
        override fun originalMs() = storedOriginal
        override fun setOriginalMs(ms: Long?) { storedOriginal = ms }
    }

    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { logs += it }

    private fun situation(
        standalone: Boolean = true,
        paired: Boolean = true,
        grant: Boolean = true,
        answer: KeepPairedAnswer = KeepPairedAnswer.NOT_ASKED,
        recording: Boolean = false,
        limit: Long? = KeepPaired.ANDROID_DEFAULT_MS,
    ) = KeepPairedPolicy.Situation(standalone, paired, grant, answer, recording, limit)

    // ---- when to ask ----

    @Test
    fun `asks once in standalone mode when paired with the limit still on`() {
        assertEquals(KeepPairedPolicy.Decision.ASK, KeepPairedPolicy.decide(situation()))
    }

    @Test
    fun `does not ask in Shizuku mode`() {
        assertEquals(KeepPairedPolicy.Decision.SKIP_SHIZUKU_MODE, KeepPairedPolicy.decide(situation(standalone = false)))
    }

    @Test
    fun `does not ask before pairing`() {
        assertEquals(KeepPairedPolicy.Decision.SKIP_NOT_PAIRED, KeepPairedPolicy.decide(situation(paired = false)))
    }

    @Test
    fun `does not ask without the secure settings grant`() {
        assertEquals(KeepPairedPolicy.Decision.SKIP_NO_GRANT, KeepPairedPolicy.decide(situation(grant = false)))
    }

    @Test
    fun `never asks again after either answer`() {
        listOf(KeepPairedAnswer.STAY_PAIRED, KeepPairedAnswer.KEEP_LIMIT).forEach {
            assertEquals(KeepPairedPolicy.Decision.SKIP_ALREADY_ANSWERED, KeepPairedPolicy.decide(situation(answer = it)))
        }
    }

    @Test
    fun `does not ask during a call`() {
        assertEquals(KeepPairedPolicy.Decision.SKIP_RECORDING, KeepPairedPolicy.decide(situation(recording = true)))
    }

    @Test
    fun `does not ask when the setting already reads zero`() {
        assertEquals(KeepPairedPolicy.Decision.SKIP_ALREADY_OFF, KeepPairedPolicy.decide(situation(limit = 0L)))
    }

    @Test
    fun `does not ask when the setting cannot be read`() {
        assertEquals(KeepPairedPolicy.Decision.SKIP_UNREADABLE, KeepPairedPolicy.decide(situation(limit = null)))
    }

    // ---- Stay paired ----

    @Test
    fun `stay paired stores the original value, writes zero and logs before and after`() {
        val limit = FakeLimit(123_000L)
        val memory = FakeMemory()
        val outcome = KeepPaired.stayPaired(limit, memory, log)
        assertEquals(KeepPaired.Outcome.DONE, outcome)
        assertEquals(0L, limit.value)
        assertEquals(123_000L, memory.storedOriginal)
        assertEquals(KeepPairedAnswer.STAY_PAIRED, memory.storedAnswer)
        assertTrue(logs.any { "123000" in it && "0" in it })
    }

    @Test
    fun `stay paired on an unreadable setting changes nothing and says why`() {
        val limit = FakeLimit(null)
        val memory = FakeMemory()
        assertEquals(KeepPaired.Outcome.UNREADABLE, KeepPaired.stayPaired(limit, memory, log))
        assertTrue(limit.writes.isEmpty())
        assertNull(memory.storedOriginal)
        assertEquals(KeepPairedAnswer.NOT_ASKED, memory.storedAnswer)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `stay paired when the write is refused keeps no ownership and does not claim success`() {
        val limit = FakeLimit(KeepPaired.ANDROID_DEFAULT_MS, writable = false)
        val memory = FakeMemory()
        assertEquals(KeepPaired.Outcome.WRITE_FAILED, KeepPaired.stayPaired(limit, memory, log))
        assertNull(memory.storedOriginal)
        assertEquals(KeepPairedAnswer.NOT_ASKED, memory.storedAnswer)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `stay paired when already zero is not ours and is left alone`() {
        val limit = FakeLimit(0L)
        val memory = FakeMemory()
        assertEquals(KeepPaired.Outcome.ALREADY_OFF, KeepPaired.stayPaired(limit, memory, log))
        assertTrue(limit.writes.isEmpty())
        assertNull(memory.storedOriginal)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `stay paired twice never overwrites the stored original with zero`() {
        val limit = FakeLimit(500L)
        val memory = FakeMemory()
        KeepPaired.stayPaired(limit, memory, log)
        KeepPaired.stayPaired(limit, memory, log)
        assertEquals(500L, memory.storedOriginal)
    }

    // ---- Keep the limit ----

    @Test
    fun `keep the limit remembers the answer and touches nothing`() {
        val limit = FakeLimit(KeepPaired.ANDROID_DEFAULT_MS)
        val memory = FakeMemory()
        KeepPaired.keepLimit(memory, log)
        assertEquals(KeepPairedAnswer.KEEP_LIMIT, memory.storedAnswer)
        assertTrue(limit.writes.isEmpty())
        assertTrue(logs.isNotEmpty())
    }

    // ---- restore ----

    @Test
    fun `restore puts back the stored original and forgets it`() {
        val limit = FakeLimit(0L)
        val memory = FakeMemory(KeepPairedAnswer.STAY_PAIRED, storedOriginal = 777L)
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.restore(limit, memory, log))
        assertEquals(777L, limit.value)
        assertNull(memory.storedOriginal)
        assertEquals(KeepPairedAnswer.KEEP_LIMIT, memory.storedAnswer)
        assertTrue(logs.any { "777" in it })
    }

    @Test
    fun `restore does nothing when CallVault did not set it`() {
        val limit = FakeLimit(0L)
        val memory = FakeMemory(KeepPairedAnswer.NOT_ASKED, storedOriginal = null)
        assertEquals(KeepPaired.Outcome.NOT_OURS, KeepPaired.restore(limit, memory, log))
        assertTrue(limit.writes.isEmpty())
        assertEquals(0L, limit.value)
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `restore that fails to write keeps the original so it can be tried again`() {
        val limit = FakeLimit(0L, writable = false)
        val memory = FakeMemory(KeepPairedAnswer.STAY_PAIRED, storedOriginal = 777L)
        assertEquals(KeepPaired.Outcome.WRITE_FAILED, KeepPaired.restore(limit, memory, log))
        assertEquals(777L, memory.storedOriginal)
        assertTrue(logs.isNotEmpty())
    }

    // ---- what the switch shows ----

    @Test
    fun `the switch is on only when the setting provably reads zero`() {
        assertTrue(KeepPaired.isOn(FakeLimit(0L)))
        assertFalse(KeepPaired.isOn(FakeLimit(KeepPaired.ANDROID_DEFAULT_MS)))
        assertFalse(KeepPaired.isOn(FakeLimit(null)))
    }

    @Test
    fun `the switch can be turned off only when CallVault set the value`() {
        assertTrue(KeepPaired.canRestore(FakeMemory(storedOriginal = 1L)))
        assertFalse(KeepPaired.canRestore(FakeMemory(storedOriginal = null)))
    }

    @Test
    fun `a mode switch to Shizuku leaves the setting alone and says so`() {
        val limit = FakeLimit(0L)
        val memory = FakeMemory(KeepPairedAnswer.STAY_PAIRED, storedOriginal = 777L)
        KeepPaired.logLeftAsIs(limit, memory, log)
        assertEquals(0L, limit.value)
        assertEquals(777L, memory.storedOriginal)
        assertTrue(logs.any { "Shizuku" in it })
    }
}
