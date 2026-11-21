/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import java.util.Locale

/**
 * Turns what somebody typed into the tag that gets stored.
 *
 * Pure, because the whole value of a tag is that the *same* label groups calls together, and every way
 * that quietly fails — a trailing space, a second capitalisation, a line break pasted in from
 * somewhere — produces two chips that look identical in a filter row and match different calls.
 */
object TagName {

    /**
     * Long enough for a phrase, short enough to stay a chip.
     *
     * Not a storage limit — it is a layout one. A tag that wraps to three lines stops being scannable
     * in the filter row, which is the only place tags are read in bulk.
     */
    const val MAX_LENGTH = 32

    /**
     * The tag to store for [input], or null when it is not a tag at all.
     *
     * [existing] is every tag already in use. When the input matches one of them apart from case, the
     * **existing spelling wins**: someone who typed `work` last month and `Work` today meant one tag,
     * and honouring the new capitalisation would silently split their filter in two. Case is preserved
     * as typed for genuinely new tags, because lowercasing everything would render `NDA` as `nda`.
     */
    fun canonical(input: String, existing: Collection<String> = emptyList()): String? {
        // Any internal whitespace, including a newline pasted from elsewhere, becomes a single space.
        val cleaned = input.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null

        val trimmed = if (cleaned.length > MAX_LENGTH) cleaned.take(MAX_LENGTH).trimEnd() else cleaned
        if (trimmed.isEmpty()) return null

        return existing.firstOrNull { it.equalsIgnoringCase(trimmed) } ?: trimmed
    }

    /** Whether [input] would produce the same tag as [other], for offering it rather than adding it. */
    fun matches(input: String, other: String): Boolean =
        canonical(input)?.equalsIgnoringCase(other) == true

    // Locale.ROOT: a tag is data, and the Turkish dotless i would otherwise make the same two tags
    // compare equal on one phone and not on another.
    private fun String.equalsIgnoringCase(other: String): Boolean = key(this) == key(other)

    /**
     * The identity of a tag, whatever its case. Lowercase with Locale.ROOT, then fold the two Turkish
     * i forms the root locale leaves apart: U+0130 lowercases to i plus a combining dot (U+0307),
     * and the dotless U+0131 stays itself. Also folds U+0131 with i, as the search line already does.
     */
    fun key(tag: String): String =
        tag.lowercase(Locale.ROOT).replace("̇", "").replace('ı', 'i')
}
