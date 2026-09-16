/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.export

/**
 * Sharing several transcripts, or several summaries, in one go.
 *
 * **One message, not one share per row.** The share sheet can attach many *files* but carries one
 * body of text, and a loop of chooser after chooser for a selection of twelve is not a feature
 * anybody would use twice. So the texts are joined, each under the name of the call it came from —
 * without which a wall of sentences from several conversations is unreadable and, worse, quotable
 * with the wrong person's name on it.
 */
object BulkTextShare {

    /**
     * The most text put straight into an `ACTION_SEND` extra.
     *
     * 🚨 **This is a crash guard, not a tidiness rule.** An Intent's extras travel through the
     * binder, whose transaction buffer is on the order of a megabyte for the whole process — and
     * exceeding it raises `TransactionTooLargeException` out of `startActivity`, which is an
     * unhandled crash at the moment the user taps Share rather than an error anyone can report.
     * Fifty long calls of transcript reach that easily. Over this, the same text goes as a file
     * instead, which travels as a URI and has no such limit.
     *
     * Well under the real ceiling on purpose: the limit is per *process*, so whatever else is in
     * flight is spending it too, and the cost of being conservative is a file rather than a failure.
     */
    const val MAX_INLINE_CHARS = 60_000

    /** One row's contribution: what the row is called, and what it has to say. */
    data class Item(val title: String, val text: String)

    /** Separates one call's text from the next — a blank line, a rule, a blank line. */
    private const val SEPARATOR = "\n\n———\n\n"

    /**
     * [items] as one body of text, each under its own title.
     *
     * Items with nothing in them are dropped rather than rendered as a title over a blank space: a
     * transcript can be a DONE row with no speech in it, and a heading with nothing under it reads
     * as text that went missing on the way.
     */
    fun join(items: List<Item>): String = items
        .filter { it.text.isNotBlank() }
        .joinToString(SEPARATOR) { "${it.title}\n\n${it.text.trim()}" }

    /**
     * Whether [text] must travel as a file rather than in the Intent itself.
     *
     * See [MAX_INLINE_CHARS] for what happens to the ones that do not.
     */
    fun mustBeAFile(text: String): Boolean = text.length > MAX_INLINE_CHARS
}
