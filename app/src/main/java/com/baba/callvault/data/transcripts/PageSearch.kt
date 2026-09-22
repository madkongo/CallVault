/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

/**
 * The pure half of the word search on the Transcripts and Summaries pages.
 *
 * Two jobs, both arithmetic on strings so they are tested without a database: what is typed
 * becomes an FTS MATCH expression, and a hit's text becomes the excerpt drawn under its row.
 */
object PageSearch {

    /** How many characters of context are shown either side of the match. */
    const val EXCERPT_RADIUS = 40

    /** An excerpt of a hit, and where in it the matched word sits ([matchStart] == [matchEnd] when it could not be found). */
    data class Excerpt(val text: String, val matchStart: Int, val matchEnd: Int)

    /**
     * [query] as a safe FTS MATCH expression: each word a quoted phrase, so what is typed is words
     * to find rather than operators, with embedded quotes doubled per SQLite's rule.
     *
     * With [prefixLast], the last word matches as a prefix — what someone still typing means by it.
     * Measured against sqlite's FTS4: a quoted prefix (`"בדי"*`) matches nothing, a bare one (`בדי*`)
     * does, so the last word goes unquoted — but only when it is letters and digits alone. Anything
     * else in it (a hyphen, a quote, a colon) is an operator once unquoted, and one such crash has
     * already been paid for.
     */
    fun matchExpression(query: String, prefixLast: Boolean): String {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        return words.mapIndexed { index, word ->
            val isLast = index == words.lastIndex
            if (prefixLast && isLast && word.all { it.isLetterOrDigit() }) "$word*"
            else "\"${word.replace("\"", "\"\"")}\""
        }.joinToString(" ")
    }

    /**
     * [text] cut to [radius] characters either side of the first typed word found in it, with an
     * ellipsis on each side that was cut. When none of the words can be found — FTS folds case and
     * diacritics that a plain comparison does not — the row still matched, so the excerpt is the
     * start of the text with nothing marked, rather than no excerpt. Blank text gives null.
     */
    fun excerpt(text: String, query: String, radius: Int = EXCERPT_RADIUS): Excerpt? {
        val body = text.trim().replace(Regex("\\s+"), " ")
        if (body.isEmpty()) return null

        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val found = words
            .map { word -> word to body.indexOf(word, ignoreCase = true) }
            .filter { (_, at) -> at >= 0 }
            .minByOrNull { (_, at) -> at }
        val (word, at) = found ?: ("" to 0)

        val from = (at - radius).coerceAtLeast(0)
        val to = (at + word.length + radius).coerceAtMost(body.length)
        val head = if (from > 0) "…" else ""
        val tail = if (to < body.length) "…" else ""
        val start = head.length + (at - from)
        return Excerpt(head + body.substring(from, to) + tail, start, start + word.length)
    }
}
