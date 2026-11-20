/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review C27: a key typed into Settings was saved only by a debounce inside the page, so closing the page
 * within the debounce (animations off) lost it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudKeySaverTest {

    private val scope = TestScope()
    private val written = mutableListOf<Pair<TranscriptionService, String>>()
    private val store = mutableMapOf<TranscriptionService, String>()
    private val saver = CloudKeySaver(
        write = { s, k -> written += s to k; store[s] = k },
        read = { store[it] },
        scope = scope,
        delayMs = 400L,
    )

    @Test
    fun `a typed key is saved once, a moment after the last keystroke`() {
        saver.typed(TranscriptionService.GROQ, "gsk_a")
        saver.typed(TranscriptionService.GROQ, "gsk_ab")
        scope.advanceTimeBy(399L)
        scope.runCurrent()
        assertEquals(emptyList<Pair<TranscriptionService, String>>(), written)
        scope.advanceUntilIdle()
        assertEquals(listOf(TranscriptionService.GROQ to "gsk_ab"), written)
    }

    @Test
    fun `closing the page before the debounce still saves the last key`() {
        saver.typed(TranscriptionService.GROQ, "gsk_secret")
        saver.flush()  // the row leaves composition 50 ms after the keystroke
        scope.runCurrent()
        assertEquals(listOf(TranscriptionService.GROQ to "gsk_secret"), written)
        // And the debounce that was pending does not write it a second time.
        scope.advanceUntilIdle()
        assertEquals(1, written.size)
    }

    @Test
    fun `closing the page with nothing unsaved writes nothing`() {
        saver.typed(TranscriptionService.GROQ, "gsk_a")
        scope.advanceUntilIdle()
        saver.flush()
        scope.advanceUntilIdle()
        assertEquals(1, written.size)
    }

    @Test
    fun `clearing the field is saved too, as a removal`() {
        saver.typed(TranscriptionService.OPENROUTER, "")
        saver.flush()
        scope.advanceUntilIdle()
        assertEquals(listOf(TranscriptionService.OPENROUTER to ""), written)
    }

    @Test
    fun `a failed save is not fatal and the next keystroke tries again`() {
        var fail = true
        val saves = mutableListOf<String>()
        val flaky = CloudKeySaver(
            write = { _, k -> if (fail) error("keystore") else saves += k },
            read = { null },
            scope = scope, delayMs = 400L,
        )
        flaky.typed(TranscriptionService.GROQ, "gsk_a")
        scope.advanceUntilIdle()
        fail = false
        flaky.typed(TranscriptionService.GROQ, "gsk_ab")
        flaky.flush()
        scope.advanceUntilIdle()
        assertEquals(listOf("gsk_ab"), saves)
    }

    /** Review of C27: after a rotation the new page loads while the old page's flush is still on its way. */
    @Test
    fun `a load right after a close shows the key that is still being saved`() {
        store[TranscriptionService.GROQ] = "gsk_old"
        saver.typed(TranscriptionService.GROQ, "gsk_new")
        saver.flush()  // queued, not yet written
        val loaded = scope.async { saver.load(TranscriptionService.GROQ) }
        scope.advanceUntilIdle()
        assertEquals("gsk_new", loaded.getCompleted())
        assertEquals("gsk_new", store[TranscriptionService.GROQ])
    }

    @Test
    fun `a load with nothing unsaved reads the store`() {
        store[TranscriptionService.GROQ] = "gsk_stored"
        val loaded = scope.async { saver.load(TranscriptionService.GROQ) }
        scope.advanceUntilIdle()
        assertEquals("gsk_stored", loaded.getCompleted())
    }

    @Test
    fun `typing a key for another service does not cancel the first one's save`() {
        saver.typed(TranscriptionService.GROQ, "gsk_a")
        saver.typed(TranscriptionService.OPENROUTER, "sk-or-b")
        saver.flush()
        scope.advanceUntilIdle()
        assertEquals(
            setOf(TranscriptionService.GROQ to "gsk_a", TranscriptionService.OPENROUTER to "sk-or-b"),
            written.toSet(),
        )
    }

    @Test
    fun `a load that finishes after typing began does not overwrite what was typed`() {
        assertEquals("typed", CloudKeySaver.fieldAfterLoad(current = "typed", loaded = "gsk_stored"))
        assertEquals("gsk_stored", CloudKeySaver.fieldAfterLoad(current = null, loaded = "gsk_stored"))
        assertEquals("", CloudKeySaver.fieldAfterLoad(current = null, loaded = null))
    }
}
