/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A note being typed: the text the field shows is the draft, not the database's echo of it.
 *
 * Binding the field to the stored value round-tripped every keystroke through Room before it was
 * shown; fast typing lost the race and letters came out swapped or missing ("hello note" typed over
 * adb read "o otehe"), and nothing said the note had been saved. Reported by a user on 2.4.1 as "you
 * cannot save a note".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteDraftTest {

    @Test
    fun `typing shows at once and saves once the typing pauses`() = runTest {
        val saved = mutableListOf<String>()
        val draft = NoteDraft(initial = "", scope = this, debounceMs = 600, save = { saved += it })

        draft.edit("h"); draft.edit("he"); draft.edit("hel")
        assertEquals("hel", draft.text)
        assertFalse(draft.isSaved)
        assertEquals(emptyList<String>(), saved)

        advanceTimeBy(601)
        assertEquals(listOf("hel"), saved)
        assertTrue(draft.isSaved)
    }

    @Test
    fun `leaving the screen saves what was typed without waiting`() = runTest {
        val saved = mutableListOf<String>()
        val draft = NoteDraft(initial = "", scope = this, debounceMs = 600, save = { saved += it })
        draft.edit("half a thou")
        draft.flush()
        assertEquals(listOf("half a thou"), saved)
        assertTrue(draft.isSaved)
        advanceTimeBy(1000)
        assertEquals(listOf("half a thou"), saved)   // the pending save was cancelled, not doubled
    }

    @Test
    fun `nothing is saved when nothing changed`() = runTest {
        val saved = mutableListOf<String>()
        val draft = NoteDraft(initial = "kept", scope = this, debounceMs = 600, save = { saved += it })
        draft.flush()
        assertEquals(emptyList<String>(), saved)
    }

    @Test
    fun `a value that changed elsewhere replaces the draft only when the draft is not being edited`() = runTest {
        // A merge writes the note through the repository; the screen should follow. But not over
        // letters being typed this second.
        val draft = NoteDraft(initial = "old", scope = this, debounceMs = 600, save = { })
        draft.stored("from a merge")
        assertEquals("from a merge", draft.text)

        draft.edit("typing")
        draft.stored("from a merge")
        assertEquals("typing", draft.text)
        advanceTimeBy(601)
        // Once saved, the stored value IS the draft; an echo of it changes nothing.
        draft.stored("typing")
        assertEquals("typing", draft.text)
    }
    @Test
    fun `the last save of a draft goes to its own recording after the screen moved on`() {
        // C121: A's draft is flushed when the screen switches to B; the latest callback already
        // writes under B, so it must not be the one used.
        val writes = mutableListOf<String>()
        val saveA: (String) -> Unit = { writes += "A:$it" }
        val saveB: (String) -> Unit = { writes += "B:$it" }
        var currentKey: Any? = "A"
        var latest = saveA
        val draftSave = ownerBoundSave("A", { currentKey }, saveA) { latest }

        currentKey = "B"
        latest = saveB
        draftSave("note about the first call")

        assertEquals(listOf("A:note about the first call"), writes)
    }

    @Test
    fun `while the recording is still shown the newest callback is used`() {
        val writes = mutableListOf<String>()
        val first: (String) -> Unit = { writes += "first:$it" }
        val second: (String) -> Unit = { writes += "second:$it" }
        var latest = first
        val draftSave = ownerBoundSave("A", { "A" }, first) { latest }

        latest = second
        draftSave("x")

        assertEquals(listOf("second:x"), writes)
    }
}
