/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.net.Uri
import com.baba.callvault.ui.navigation.HomeSection
import com.baba.callvault.ui.navigation.LibrarySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Encoding the Home screen's state so it survives a rotation.
 *
 * Worth testing as pure functions rather than through Compose: `rememberSaveable` restoring a value
 * is the framework's guarantee, but *what we hand it* is ours. A selection that decodes to the wrong
 * set is a multi-select pointing at recordings the user did not choose, and the next action on it is
 * a delete — so a lossy round-trip here is destructive, not cosmetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StateSaversTest {

    // ---- selection (Set<Uri>) ----

    @Test
    fun `selection survives a round-trip`() {
        val selection = setOf(
            Uri.parse("content://recordings/a.ogg"),
            Uri.parse("content://recordings/b.ogg"),
        )
        assertEquals(selection, decodeUriSet(encodeUriSet(selection)))
    }

    @Test
    fun `an empty selection round-trips to empty, not to null`() {
        assertEquals(emptySet<Uri>(), decodeUriSet(encodeUriSet(emptySet<Uri>())))
    }

    @Test
    fun `a uri containing separators and spaces survives`() {
        // A naive "join with a comma" encoding passes every other test and loses these. Real
        // recording names carry spaces, plus signs and encoded characters.
        val awkward = setOf(
            Uri.parse("content://recordings/20260904 in +1602,609.ogg"),
            Uri.parse("content://recordings/%2Fnested%2Fname.ogg"),
        )
        assertEquals(awkward, decodeUriSet(encodeUriSet(awkward)))
    }

    @Test
    fun `restoring does not depend on ordering`() {
        val a = Uri.parse("content://recordings/a.ogg")
        val b = Uri.parse("content://recordings/b.ogg")
        assertEquals(decodeUriSet(encodeUriSet(setOf(a, b))), decodeUriSet(encodeUriSet(setOf(b, a))))
    }

    // ---- pending transcribe confirmation (name, estimate, language) ----

    @Test
    fun `a transcribe request survives with every field set`() {
        val request = Triple("call.ogg", 90_000L, "he")
        assertEquals(request, decodeTranscribeRequest(encodeTranscribeRequest(request)))
    }

    @Test
    fun `a null estimate stays null rather than becoming zero`() {
        // Zero would be quoted to the user as "this will take about 0 seconds".
        val restored = decodeTranscribeRequest(encodeTranscribeRequest(Triple("call.ogg", null, "en")))
        assertNull(restored.second)
    }

    @Test
    fun `a null language stays null so the setting still decides`() {
        // "" is not the same as null here: null means "use the configured language".
        val restored = decodeTranscribeRequest(encodeTranscribeRequest(Triple("call.ogg", 1_000L, null)))
        assertNull(restored.third)
    }

    @Test
    fun `a zero estimate is preserved and not confused with null`() {
        assertEquals(0L, decodeTranscribeRequest(encodeTranscribeRequest(Triple("c.ogg", 0L, null))).second)
    }

    @Test
    fun `a name containing the field separator survives`() {
        val request = Triple("weird|name.ogg", 5L, "en")
        assertEquals(request, decodeTranscribeRequest(encodeTranscribeRequest(request)))
    }

    // ---- delete scope (enum) ----

    @Test
    fun `every delete scope round-trips`() {
        com.baba.callvault.data.recordings.DeleteScope.entries.forEach { scope ->
            assertEquals(scope, decodeDeleteScope(encodeDeleteScope(scope)))
        }
    }

    @Test
    fun `an unknown stored delete scope falls back to the safe default`() {
        // A downgrade or a renamed enum constant must not crash the dialog.
        assertEquals(com.baba.callvault.data.recordings.DeleteScope.BOTH, decodeDeleteScope("NO_SUCH_SCOPE"))
    }

    @Test
    fun `encoded forms are plain strings so the framework can bundle them`() {
        assertTrue(encodeUriSet(setOf(Uri.parse("content://x"))).all { it is String })
        assertTrue(encodeTranscribeRequest(Triple("a", 1L, "b")).all { it is String })
        assertTrue(encodeLibrarySelection(LibrarySelection.EMPTY).all { it is String })
    }

    @Test
    fun `a library selection round-trips with the page it was picked on`() {
        // The page has to survive with the names: a restored set with no page attached could be
        // acted on from either library list, and the next tap after a selection is a delete.
        val selection = LibrarySelection(
            section = HomeSection.Summaries,
            names = setOf("a.ogg", "b with spaces + plus | pipe.m4a"),
        )
        assertEquals(selection, decodeLibrarySelection(encodeLibrarySelection(selection)))
    }

    @Test
    fun `an empty library selection round-trips to nothing selected`() {
        assertEquals(
            LibrarySelection.EMPTY,
            decodeLibrarySelection(encodeLibrarySelection(LibrarySelection.EMPTY))
        )
    }

    @Test
    fun `a stored section key that no longer exists means nothing is selected`() {
        // A downgrade or a renamed section. Restoring rows picked under a page we can no longer
        // place, with a delete button over them, is the outcome this refuses.
        assertEquals(
            LibrarySelection.EMPTY,
            decodeLibrarySelection(listOf("no-such-section", "a.ogg"))
        )
    }
}
