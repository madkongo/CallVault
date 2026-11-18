/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

/**
 * The shape a summary is physically unable to break.
 *
 * Asked politely for strict JSON, Gemma returned a `summary` string with no closing quote and the
 * whole object failed to parse. That is not a wording problem: a small model under a token cap drops
 * a quote, and "return valid JSON" is a request. A grammar is not a request — tokens that would
 * break the structure are removed from the candidates before the pick, so malformed output cannot be
 * produced at all.
 *
 * **The keys are fixed and ordered.** A grammar cannot express "these keys in any order" without
 * becoming large and slow, and fixing the order costs nothing: the model has no reason to prefer one.
 * It also means a parser can rely on every key existing, which removes a whole class of null checks.
 *
 * **Two fields from the original design are absent, deliberately.**
 * - `participants`: measured, it read the ringtone marker `*ביב*` as a person and named them.
 *   Confidently wrong names are worse than no names. It returns when diarisation can supply them.
 * - `sentiment`: low value, and being confidently wrong about the tone of someone's private
 *   conversation is a bad trade for a word nobody asked for.
 */
object SummaryGrammar {

    /**
     * The most items a single list may hold, enforced rather than requested.
     *
     * [SummaryPrompt] asks for this same number in words, and takes it from here so the two cannot
     * disagree — a prompt asking for one figure while the grammar permits another is how the last
     * round of this drift started.
     */
    const val MAX_LIST_ITEMS = 8

    /**
     * GBNF for the summary object.
     *
     * **Every rule is on one line.** GBNF ends a rule at the newline, so a `root` spread over
     * several lines parses as several broken rules — "expecting name". Worse, that failure is
     * silent in practice: the native side logs it and generates unconstrained, so the output looks
     * plausible and the constraint simply is not there. It was caught by running the grammar
     * through llama-cli on a desktop, which refuses to start rather than carrying on.
     *
     * `string` allows the standard JSON escapes, so a Hebrew summary containing a quotation mark
     * cannot produce a document that fails to parse — which is the entire point of being here.
     *
     * **The arrays are bounded, and that is what stops the repetition loop.** They used to be `*`,
     * so "at most N items" was only a request in the prompt while the grammar permitted infinitely
     * many — which is the textbook setup for the failure we actually saw ("four decisions, three of
     * them the same sentence"). Holtzman et al. (ICLR 2020, Table 1) measured greedy decoding
     * falling into a repetition loop in 73.66% of generations, the worst configuration in the paper.
     * `{0,n}` is real GBNF — `parse_sequence` in the pinned `llama-grammar.cpp` handles `{m,n}` and
     * rewrites it into n optional rules — so a first item followed by at most
     * [MAX_LIST_ITEMS] - 1 more terminates by construction.
     *
     * Bounding it here rather than in the sampler is deliberate. `repeat_penalty` cannot tell
     * "looping" from "listing the eighth item"; it would buy termination by making long lists
     * impossible, which is the opposite of what the lists are for.
     */
    /**
     * Longest a `summary` may be, in characters. Roughly three hundred words — enough for a language
     * that needs more of them, and far short of the token budget the chunk is given.
     */
    private const val MAX_SUMMARY_CHARS = 1_200

    /** Longest any other string may be. A list item or an intent is a line, not a paragraph. */
    private const val MAX_ITEM_CHARS = 300

    /**
     * Longest run of whitespace permitted between tokens.
     *
     * Unbounded `ws` lets the model emit spaces for ever without ever advancing the structure — a
     * stall the grammar itself permits. Twenty is far more than valid JSON ever needs.
     */
    private const val MAX_WS = 20

    /**
     * 🚨 **Never write a repetition bound above this.** `llama-grammar.cpp` defines
     * `MAX_REPETITION_THRESHOLD = 2000`, and a `max_times` **above** it is silently rewritten to
     * `UINT64_MAX` — unbounded. So `char{0,5000}` does not mean "at most 5000", it means "no limit",
     * and reintroduces exactly the defect the bound was added to fix, invisibly. Separately, if
     * `previous rules × bound` reaches 2000 the parser *throws*, and a grammar that fails to parse
     * falls back to generating unconstrained. Both roads lead back to the same silent bug.
     */
    private const val REPETITION_CEILING = 2_000

    init {
        require(MAX_SUMMARY_CHARS < REPETITION_CEILING) { "a bound at or above $REPETITION_CEILING is silently ignored" }
        require(MAX_ITEM_CHARS < REPETITION_CEILING) { "a bound at or above $REPETITION_CEILING is silently ignored" }
        require(MAX_WS < REPETITION_CEILING) { "a bound at or above $REPETITION_CEILING is silently ignored" }
    }

    val JSON: String = """
        root ::= "{" ws "\"intent\":" ws item "," ws "\"summary\":" ws summary "," ws "\"keyPoints\":" ws array "," ws "\"decisions\":" ws array "," ws "\"actionItems\":" ws array "," ws "\"keyFacts\":" ws array ws "}"
        array ::= "[" ws (item (ws "," ws item){0,${MAX_LIST_ITEMS - 1}})? ws "]"
        summary ::= "\"" char{0,$MAX_SUMMARY_CHARS} "\""
        item ::= "\"" char{0,$MAX_ITEM_CHARS} "\""
        char ::= [^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" hex hex hex hex)
        hex ::= [0-9a-fA-F]
        ws ::= [ \t\n]{0,$MAX_WS}
    """.trimIndent()

    /**
     * [JSON] plus a last `tags` key (BabApps item 136): 0 to [CallSummary.MAX_TAGS] suggestions of 1
     * to [CallSummary.MAX_TAG_CHARS] characters each, with no quote, backslash, bracket or control
     * character (so no newline) possible inside one.
     *
     * Only the **final** pass uses it: a one-chunk call's only generation, or the merge of a long
     * call. The chunks of a long call keep [JSON] unchanged, so they cost no extra tokens and cannot
     * be pushed off course by them. `tags` is the last key, so nothing generated before it can be
     * influenced by it; `[]` stays possible because forcing one tag on a near-empty call only
     * invents a word.
     */
    val JSON_WITH_TAGS: String = JSON
        .replace(
            """"keyFacts\":" ws array ws "}"""",
            """"keyFacts\":" ws array "," ws "\"tags\":" ws tags ws "}""""
        ) + "\n" + """
        tags ::= "[" ws (tag (ws "," ws tag){0,${CallSummary.MAX_TAGS - 1}})? ws "]"
        tag ::= "\"" tchar{1,${CallSummary.MAX_TAG_CHARS}} "\""
        tchar ::= [^"\\\x00-\x1F\[\]]
    """.trimIndent()
}
