/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import com.baba.callvault.BuildConfig
import com.baba.callvault.ui.common.ReleaseHighlights
import com.baba.callvault.ui.viewmodels.HomeViewModel.Companion.isWhatsNewDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers when the post-update release note appears.
 *
 * The rule that matters is that it is not a nag: it shows once for a version, right after that version
 * is installed, and never again on later launches of the same build. Keyed on the version it was last
 * shown for, so a new release brings it back without anyone having to add a new preference.
 */
class WhatsNewSelectionTest {

    @Test
    fun `not shown when no update just landed`() {
        assertFalse(isWhatsNewDue(justUpdated = false, currentVersion = "1.4.7", seenForVersion = null))
    }

    @Test
    fun `shown after an update when never seen`() {
        assertTrue(isWhatsNewDue(justUpdated = true, currentVersion = "1.4.7", seenForVersion = null))
    }

    @Test
    fun `not shown again once seen for this version`() {
        assertFalse(isWhatsNewDue(justUpdated = true, currentVersion = "1.4.7", seenForVersion = "1.4.7"))
    }

    @Test
    fun `shown again for the next version`() {
        assertTrue(isWhatsNewDue(justUpdated = true, currentVersion = "1.4.8", seenForVersion = "1.4.7"))
    }

    @Test
    fun `still not shown outside an update even when the seen version is older`() {
        assertFalse(isWhatsNewDue(justUpdated = false, currentVersion = "1.4.8", seenForVersion = "1.4.7"))
    }
}

/** Covers the note's content, which is hand-maintained alongside the changelog. */
class ReleaseHighlightsTest {

    @Test
    fun `shows no more releases than it promises to`() {
        assertTrue(ReleaseHighlights.recent().size <= ReleaseHighlights.MAX_SHOWN)
    }

    /**
     * Ordering is the invariant, not any particular version.
     *
     * This assertion used to be `assertEquals("1.5.0", …first().version)`, which failed on every
     * release that added a note and never checked ordering at all — a note inserted in the wrong
     * place passed it happily, as long as the top entry was unchanged. Comparing each pair instead
     * catches the misplacement and needs no edit at release time.
     */
    @Test
    fun `lists the newest release first`() {
        val versions = ReleaseHighlights.recent().map { it.version }
        versions.zipWithNext { newer, older ->
            assertTrue(
                "$newer should sort above $older in the release note",
                compareVersions(newer, older) > 0,
            )
        }
    }

    /** Numeric, component-wise: "1.5.10" is newer than "1.5.9", which string ordering gets wrong. */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.toInt() }
        val right = b.split(".").map { it.toInt() }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    @Test
    fun `labels every entry with a version`() {
        assertTrue(ReleaseHighlights.recent().all { it.version.isNotBlank() })
    }

    /**
     * The 2.2.0 hole: the version was bumped without a card, so users saw the previous release's note as
     * news. Compared on the base version, so a test build such as 2.5.1-rc4 still has to match 2.5.1.
     */
    @Test
    fun `newest card is for the version being built`() {
        val base = BuildConfig.VERSION_NAME.substringBefore('-')
        assertEquals(base, ReleaseHighlights.recent().first().version)
    }

    @Test
    fun `lists no release twice`() {
        val versions = ReleaseHighlights.recent().map { it.version }
        assertEquals(versions.size, versions.distinct().size)
    }
}
