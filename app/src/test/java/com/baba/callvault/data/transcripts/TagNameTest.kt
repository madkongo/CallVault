/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one way tagging fails: two chips that look the same and match different calls.
 *
 * None of this throws or crashes, which is exactly why it needs pinning down — a split tag is
 * invisible until somebody filters by *work* and finds half of what they filed under it.
 */
class TagNameTest {

    @Test
    fun `an existing tag's spelling wins over a new capitalisation`() {
        // The headline case. Someone typed "work" in March and "Work" in August, and meant one tag.
        assertEquals("work", TagName.canonical("Work", existing = listOf("work")))
        assertEquals("work", TagName.canonical("WORK", existing = listOf("work")))
        assertEquals("work", TagName.canonical("  work  ", existing = listOf("work")))
    }

    @Test
    fun `a genuinely new tag keeps the case it was typed in`() {
        // The reason this is not simply lowercased: "nda" reads as a typo.
        assertEquals("NDA", TagName.canonical("NDA", existing = listOf("work")))
        assertEquals("Dad's doctor", TagName.canonical("Dad's doctor"))
    }

    @Test
    fun `surrounding and repeated whitespace is not part of the tag`() {
        assertEquals("the flat", TagName.canonical("  the   flat "))
    }

    @Test
    fun `a pasted line break does not become part of the tag`() {
        // Paste is how this reaches the field in practice, and a tag with a newline in it renders as
        // a chip that grows to two lines and matches nothing anybody would type by hand.
        assertEquals("the flat", TagName.canonical("the\nflat"))
    }

    @Test
    fun `nothing typed is not a tag`() {
        assertNull(TagName.canonical(""))
        assertNull(TagName.canonical("   "))
        assertNull(TagName.canonical("\n\t "))
    }

    @Test
    fun `an over-long tag is cut to fit a chip and does not end mid-space`() {
        val long = "a".repeat(TagName.MAX_LENGTH + 20)

        val result = TagName.canonical(long)

        assertEquals(TagName.MAX_LENGTH, result?.length)
    }

    @Test
    fun `truncation does not leave a trailing space`() {
        val awkward = "x".repeat(TagName.MAX_LENGTH - 1) + " more words"

        val result = TagName.canonical(awkward)

        assertEquals(result, result?.trim())
    }

    @Test
    fun `case-insensitive matching does not depend on the phone's locale`() {
        // The Turkish dotless i: lowercasing "I" in a Turkish locale yields a different character, so
        // a locale-sensitive comparison would merge two tags on one phone and not on another.
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))

            assertEquals("Insurance", TagName.canonical("insurance", existing = listOf("Insurance")))
        } finally {
            java.util.Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `turkish case variants of one word are one tag`() {
        // C148: U+0130 lowercases to i + U+0307 and the dotless U+0131 stays apart from I under
        // Locale.ROOT, so these pairs counted as different tags and the suggestion chip offered a
        // tag the call already carried.
        assertEquals("İnşaat", TagName.canonical("inşaat", existing = listOf("İnşaat")))
        assertEquals("ılık", TagName.canonical("ILIK", existing = listOf("ılık")))
        assertTrue(TagName.matches("İNŞAAT", "inşaat"))
        assertTrue(TagName.matches("ILIK", "ılık"))
    }

    @Test
    fun `different words are still different tags`() {
        assertEquals("home", TagName.canonical("home", existing = listOf("hone")))
    }

    @Test
    fun `matches reports whether typing this would reuse an existing tag`() {
        assertTrue(TagName.matches("  WORK ", "work"))
    }

    @Test
    fun `renaming only the capitalisation is not swallowed as a no-op`() {
        // The rename path excludes the tag being renamed from the existing set, so correcting "work"
        // to "Work" is a real change. Without that exclusion it would canonicalise straight back to
        // "work" and the user would tap Rename and watch nothing happen, twice, and give up.
        val othersExcludingItself = listOf("insurance", "home")

        assertEquals("Work", TagName.canonical("Work", existing = othersExcludingItself))
    }

    @Test
    fun `an unrelated existing tag is left alone`() {
        assertEquals("home", TagName.canonical("home", existing = listOf("work", "insurance")))
    }
}
