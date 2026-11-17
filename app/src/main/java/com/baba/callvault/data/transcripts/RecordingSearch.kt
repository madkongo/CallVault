/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

/**
 * The pure half of the one search line on the Recordings page: why a recording matched, and in
 * what order the matches come.
 *
 * The line searches four places. They are ranked, best first: the recording's own name or number,
 * then its tags, then its summary, then its transcript. A recording found in several places is
 * listed once, under the best of them, so the order never repeats a call and the reason shown is
 * always the strongest one.
 *
 * Kept free of the database and of Android so the ranking is tested on plain strings; the two
 * full-text lookups that feed it are [TranscriptRepository.searchContent].
 */
object RecordingSearch {

    /** Where a recording matched. The declaration order IS the ranking: earlier is better. */
    enum class Kind { NAME, TAG, SUMMARY, TRANSCRIPT }

    /**
     * Why one recording is in the result: the [kind] of place, and for everything but the name an
     * [excerpt] to draw under the row (the tag itself, or the words around the match).
     */
    data class Why(val kind: Kind, val excerpt: PageSearch.Excerpt?)

    /** What the full-text indexes found for one recording; a null side means that index did not match. */
    data class ContentHit(val summary: PageSearch.Excerpt?, val transcript: PageSearch.Excerpt?)

    /**
     * The best reason [displayName] matches [query], or null when it does not match at all.
     *
     * @param nameMatches whether the contact name, number or file name already contains the query
     *   (decided by the caller, which owns how a recording is labelled).
     * @param tags the recording's tags; the first one containing the typed text wins.
     * @param content what the summary and transcript indexes found for it, if anything.
     */
    fun classify(
        query: String,
        nameMatches: Boolean,
        tags: Set<String>?,
        content: ContentHit?,
    ): Why? {
        if (nameMatches) return Why(Kind.NAME, null)

        val typed = query.trim()
        if (typed.isNotEmpty() && tags != null) {
            val tag = tags.sortedWith(String.CASE_INSENSITIVE_ORDER).firstOrNull { it.contains(typed, ignoreCase = true) }
            if (tag != null) {
                val at = tag.indexOf(typed, ignoreCase = true)
                return Why(Kind.TAG, PageSearch.Excerpt(tag, at, at + typed.length))
            }
        }
        content?.summary?.let { return Why(Kind.SUMMARY, it) }
        content?.transcript?.let { return Why(Kind.TRANSCRIPT, it) }
        return null
    }

    /**
     * [items] in ranking order: by [Kind] first, and within one kind in the order they came in, so
     * the page's own sort (newest, oldest, name, longest) still decides between equally good hits.
     * Items without a reason are dropped; each item appears once because [whyOf] gives one answer.
     */
    fun <T> ranked(items: List<T>, whyOf: (T) -> Why?): List<Pair<T, Why>> =
        items.mapNotNull { item -> whyOf(item)?.let { item to it } }
            .sortedBy { (_, why) -> why.kind.ordinal } // stable: ties keep their incoming order
}
