/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import com.baba.callvault.data.transcripts.TagName
import org.json.JSONArray
import org.json.JSONObject

/**
 * A model's account of a call, once it has stopped being text and become data.
 *
 * The fields are the ones [SummaryGrammar] can physically produce, and no others. Two from the
 * original design are absent on purpose — `participants`, because it read a ringtone marker as a
 * person and named them, and `sentiment`, because being confidently wrong about the tone of
 * someone's private conversation is a bad trade. [parse] ignores both if an older prompt asks for
 * them, so their return is a change to the grammar rather than a migration.
 *
 * **Parsed with `org.json`, not a serialization library.** The shape is six known keys produced
 * under a grammar; that does not justify adding a compiler plugin to the build. Room stores the
 * document [toJson] returns, so encoding and decoding are the same code path the model's own output
 * takes — one parser, exercised by every summary that has ever been read.
 */
data class CallSummary(
    /** One line on why the call happened, used as the card's headline. */
    val intent: String,
    /** The account itself, in prose. Never blank — see [parse]. */
    val summary: String,
    val keyPoints: List<String>,
    val decisions: List<String>,
    val actionItems: List<String>,
    val keyFacts: List<String>,
    /**
     * Tags the model *suggests* for the call (BabApps item 136). Never applied by themselves: the
     * card shows them as chips and a tap writes one to `recording_tags`. Empty for every summary
     * written before this key existed, and for the chunks of a long call (only the final pass asks).
     */
    val tags: List<String> = emptyList(),
    /**
     * Suggestions the user dismissed, kept in the summary document so they survive a re-summarise
     * without a second table. [com.baba.callvault.summary.SummaryRunner] carries them to the new document.
     */
    val dismissedTags: List<String> = emptyList()
) {

    /** The same summary with [transform] applied to every piece of text (Chinese script conversion, item 125). */
    fun mapText(transform: (String) -> String): CallSummary = CallSummary(
        intent = transform(intent),
        summary = transform(summary),
        keyPoints = keyPoints.map(transform),
        decisions = decisions.map(transform),
        actionItems = actionItems.map(transform),
        keyFacts = keyFacts.map(transform),
        tags = tags.map(transform),
        dismissedTags = dismissedTags,
    )

    /**
     * The suggestions worth showing: not dismissed, and not already on the recording.
     *
     * Compared the way [TagName] compares (trimmed, case-insensitive), so "Plumbing" is not offered
     * to a call already tagged "plumbing", and a dismissed word stays dismissed whatever its case.
     */
    fun suggestedTags(existing: Collection<String>): List<String> =
        tags.filter { tag ->
            existing.none { TagName.matches(tag, it) } && dismissedTags.none { TagName.matches(tag, it) }
        }

    /** This summary, with what [previous] (an older summary of the same call) had dismissed kept dismissed. */
    fun inheritingDismissed(previous: List<String>): CallSummary =
        previous.fold(copy(dismissedTags = emptyList())) { acc, tag -> acc.withDismissed(tag) }

    /** This summary with [tag] added to the dismissed list (once). */
    fun withDismissed(tag: String): CallSummary =
        if (dismissedTags.any { TagName.matches(tag, it) }) this
        else copy(dismissedTags = dismissedTags + (TagName.canonical(tag) ?: tag))

    /**
     * The summary as a JSON document, in the shape [parse] accepts.
     *
     * `org.json` escapes quotation marks, commas and newlines, all of which occur in ordinary
     * speech. A list joined on a delimiter would corrupt the first time someone was quoted.
     */
    fun toJson(): String = JSONObject()
        .put(KEY_INTENT, intent)
        .put(KEY_SUMMARY, summary)
        .put(KEY_POINTS, JSONArray(keyPoints))
        .put(KEY_DECISIONS, JSONArray(decisions))
        .put(KEY_ACTIONS, JSONArray(actionItems))
        .put(KEY_FACTS, JSONArray(keyFacts))
        .put(KEY_TAGS, JSONArray(tags))
        .put(KEY_DISMISSED_TAGS, JSONArray(dismissedTags))
        .toString()

    /**
     * Every word a person wrote or the model said, and none of the scaffolding, for the search index.
     *
     * **Why this exists rather than indexing [toJson] directly.** The stored document is JSON, so its
     * text contains the six key names and `org.json`'s escapes. Indexing it would make *summary* and
     * *decisions* match every call that has a summary at all — a search box that returns everything is
     * worse than one that returns nothing, because the user cannot tell it is broken.
     *
     * Fields are joined on newlines rather than spaces so that no phrase spans two of them: FTS
     * tokenises on non-alphanumerics either way, but a phrase query (`"..."`) would otherwise match
     * across a boundary that does not exist in the text anyone read.
     */
    fun searchableText(): String =
        (listOf(intent, summary) + keyPoints + decisions + actionItems + keyFacts)
            .filter { it.isNotBlank() }
            .joinToString("\n")

    companion object {

        private const val KEY_INTENT = "intent"
        private const val KEY_SUMMARY = "summary"
        private const val KEY_POINTS = "keyPoints"
        private const val KEY_DECISIONS = "decisions"
        private const val KEY_ACTIONS = "actionItems"
        private const val KEY_FACTS = "keyFacts"
        private const val KEY_TAGS = "tags"
        private const val KEY_DISMISSED_TAGS = "dismissedTags"

        /** The most suggestions kept, whatever the model wrote. The grammar already allows no more. */
        const val MAX_TAGS = 3

        /** Longest suggestion kept, in characters; the grammar's own tag rule uses the same figure. */
        const val MAX_TAG_CHARS = 24

        private val REQUIRED = listOf(KEY_INTENT, KEY_SUMMARY, KEY_POINTS, KEY_DECISIONS, KEY_ACTIONS, KEY_FACTS)

        /**
         * What a document must still have after it has been closed by hand.
         *
         * The lists are dropped from the requirement because a list the generation never reached is
         * honestly empty — `[]` is exactly what the prompt asks for when there is nothing, and
         * [stringsIn] already returns that for a key that is not there. The prose is not dropped:
         * `intent` and `summary` come first under the grammar's fixed key order, so if they survived
         * the cut they survived it whole, and if they did not there is nothing worth showing.
         */
        private val REQUIRED_WHEN_CLOSED = listOf(KEY_INTENT, KEY_SUMMARY)

        /**
         * Combines already-parsed parts into one summary, without asking the model again.
         *
         * The floor under the merge pass. That pass is a second generation and so a second chance to
         * fail — it can truncate against its token budget, and the result is then *no summary at
         * all* after minutes of work the user sat and watched. Concatenating what already parsed is
         * worse than a written merge and enormously better than nothing.
         *
         * The first part names the call: the opening is where someone says why they rang, so the
         * earliest non-empty intent is the call's. Identical lines across parts are dropped, because
         * a summary that says the same thing twice reads as padding.
         */
        fun concatenate(parts: List<CallSummary>): CallSummary? {
            if (parts.isEmpty()) return null
            parts.singleOrNull()?.let { return it }

            return CallSummary(
                intent = parts.firstOrNull { it.intent.isNotBlank() }?.intent.orEmpty(),
                summary = parts.map { it.summary }.filter { it.isNotBlank() }.distinct().joinToString(" "),
                keyPoints = parts.flatMap { it.keyPoints }.distinct(),
                decisions = parts.flatMap { it.decisions }.distinct(),
                actionItems = parts.flatMap { it.actionItems }.distinct(),
                keyFacts = parts.flatMap { it.keyFacts }.distinct(),
                tags = parts.flatMap { it.tags }.distinct().take(MAX_TAGS)
            )
        }

        /**
         * The summary in [raw], or `null` if there isn't a whole one.
         *
         * Returning `null` rather than a partially filled object is the whole point. Under a token
         * cap a small model drops its closing quote, and a card built from the fragment would show
         * a sentence that stops mid-word as though the call had been summarised. The grammar makes
         * that unsampleable while it is applied — but a row written before the grammar existed, or
         * by a run where it failed to compile, still has to be refused on the way out.
         *
         * Tolerant about everything that isn't the shape: a reasoning block, prose around the
         * object, a code fence, and keys we don't know are all fine. Strict about exactly two
         * things — every expected key present, and prose actually in it.
         *
         * **A document that only ran out of tokens is not refused.** `keyFacts` is required and the
         * fixed key order puts it last, so an answer that hit its budget used to cost the whole
         * chunk over a missing brace — worst in Hebrew and Arabic, where the same content costs more
         * tokens. [SummaryText.closeTruncated] closes what was open, dropping the item that was
         * being written, and the result is judged by [REQUIRED_WHEN_CLOSED] instead. It is still a
         * refusal, only a later one: a cut that took the prose with it still returns null.
         */
        fun parse(raw: String): CallSummary? {
            val text = SummaryText.stripReasoning(raw)
            objectIn(text)?.let { json -> return from(json, REQUIRED) }

            val closed = SummaryText.closeTruncated(text) ?: return null
            return objectIn(closed)?.let { json -> from(json, REQUIRED_WHEN_CLOSED) }
        }

        /** The summary in [json], or null when [required] is not all there or the prose is blank. */
        private fun from(json: JSONObject, required: List<String>): CallSummary? {
            if (required.any { !json.has(it) }) return null

            val summary = json.optString(KEY_SUMMARY).trim()
            if (summary.isEmpty()) return null

            return CallSummary(
                intent = json.optString(KEY_INTENT).trim(),
                summary = summary,
                keyPoints = stringsIn(json, KEY_POINTS),
                decisions = stringsIn(json, KEY_DECISIONS),
                actionItems = stringsIn(json, KEY_ACTIONS),
                keyFacts = stringsIn(json, KEY_FACTS),
                tags = cleanTags(stringsIn(json, KEY_TAGS)),
                dismissedTags = stringsIn(json, KEY_DISMISSED_TAGS)
            )
        }

        /**
         * Suggestions as they will be shown and stored: through [TagName.canonical] (one spelling
         * rule for typed and suggested tags), no case-duplicates, at most [MAX_TAGS] of
         * [MAX_TAG_CHARS] characters. Not required by [parse]: a summary without the key is simply
         * one with nothing to suggest.
         */
        private fun cleanTags(raw: List<String>): List<String> =
            raw.mapNotNull { TagName.canonical(it.take(MAX_TAG_CHARS)) }
                .distinctBy { it.lowercase(java.util.Locale.ROOT) }
                .take(MAX_TAGS)

        /**
         * The outermost JSON object in [text], or `null`.
         *
         * Spans the first `{` to the last `}` so a code fence or a closing pleasantry is simply not
         * included, rather than having to be recognised and stripped.
         */
        private fun objectIn(text: String): JSONObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
        }

        /**
         * The non-blank strings at [key].
         *
         * Blanks are dropped rather than kept: an empty string renders as a bullet with nothing
         * beside it, which reads as a fact the app has lost rather than one the model never had.
         */
        private fun stringsIn(json: JSONObject, key: String): List<String> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length())
                .mapNotNull { array.optString(it).trim().ifEmpty { null } }
                .filterNot(::isJsonFragment)
        }

        /** The list keys [stringsIn] filters, in the order [from] reads them. */
        private val LIST_KEYS = listOf(KEY_POINTS, KEY_DECISIONS, KEY_ACTIONS, KEY_FACTS, KEY_TAGS, KEY_DISMISSED_TAGS)

        /**
         * How many list items in [raw] [isJsonFragment] removes, for the run's log; a count, never
         * the text. Reads [raw] the way [parse] does, and is 0 when there is no object in it.
         */
        internal fun droppedFragmentCount(raw: String): Int {
            val text = SummaryText.stripReasoning(raw)
            val json = objectIn(text) ?: SummaryText.closeTruncated(text)?.let(::objectIn) ?: return 0
            return LIST_KEYS.sumOf { key ->
                val array = json.optJSONArray(key) ?: return@sumOf 0
                (0 until array.length()).count { i ->
                    val item = array.optString(i).trim()
                    item.isNotEmpty() && isJsonFragment(item)
                }
            }
        }

        private const val KEY_NAMES = "summary|keyPoints|decisions|actionItems|keyFacts|tags|intent"

        /** A `[m:ss]` citation, the shape [SummaryCitations] keeps in the text. Removed before testing. */
        private val CITATION = Regex("""\[\d{1,2}:\d{1,2}(?::\d{1,2})?]""")

        /** (R1) A quoted key followed by a colon: `"actionItems": [`. */
        private val QUOTED_KEY_THEN_COLON = Regex("(?i)\"\\s*($KEY_NAMES)\\s*\"\\s*:")

        /** (R2) A key, then a closing quote, bracket or brace, then a colon: `decisions]: [`, `keyFacts":`. */
        private val KEY_CLOSED_THEN_COLON = Regex("(?i)\\b($KEY_NAMES)\\s*[\"\\]}]+\\s*:")

        /** (R3) A key name followed by a colon and nothing else of substance: `decisions:`. */
        private val KEY_THEN_NOTHING = Regex("(?i)\\b($KEY_NAMES)\\s*:[\\p{P}\\p{S}\\s]*$")

        /**
         * (R4) Nothing but key names and JSON punctuation, with at least one quote, bracket, brace or
         * colon: `[tags`, `"tags"`, `}, "actionItems`. A lone word ("Summary") is a real item.
         */
        private val ONLY_KEYS_AND_STRUCTURE = Regex(
            "(?i)^[\\s\"\\[\\]{}:,]*($KEY_NAMES)([\\s\"\\[\\]{}:,]+($KEY_NAMES))*[\\s\"\\[\\]{}:,]*$"
        )
        private val JSON_STRUCTURE = Regex("[\"\\[\\]{}:]")

        /**
         * (R5) The seam between two JSON members written into one string: a closing bracket or brace,
         * a comma, and an opening quote before a key, as in `Real point"], "decisions`.
         */
        private val MEMBER_SEAM_BEFORE_KEY = Regex("(?i)[\\]}]\\s*,\\s*\"\\s*($KEY_NAMES)\\b")

        /**
         * An item that is a piece of the JSON's own structure rather than content: only punctuation
         * and brackets (`],`), or a key name written into the string in key position
         * (`decisions]: [`, `"actionItems": [`). A sentence that merely mentions a key word stays,
         * also beside a quote, an apostrophe or a `[m:ss]` citation: "We made two decisions: A and B",
         * `[1:30] Summary of the quote`, `Both parties' decisions are final` (review C18).
         *
         * Only decides; the stored item is never rewritten. Citations are removed from a copy so a
         * marker's brackets never count as structure, and an apostrophe never counts at all.
         */
        internal fun isJsonFragment(item: String): Boolean {
            if (item.none { it.isLetterOrDigit() }) return true
            val bare = CITATION.replace(item, " ").trim()
            return QUOTED_KEY_THEN_COLON.containsMatchIn(bare) ||
                KEY_CLOSED_THEN_COLON.containsMatchIn(bare) ||
                KEY_THEN_NOTHING.containsMatchIn(bare) ||
                (ONLY_KEYS_AND_STRUCTURE.matches(bare) && JSON_STRUCTURE.containsMatchIn(bare)) ||
                MEMBER_SEAM_BEFORE_KEY.containsMatchIn(bare)
        }
    }
}
