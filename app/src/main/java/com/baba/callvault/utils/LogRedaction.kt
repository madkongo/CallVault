/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import java.security.MessageDigest

/**
 * The rules that make a log line safe to paste into a public GitHub issue.
 *
 * Pure logic, no Android and no state, so it can be unit tested directly — [AppLogger] only supplies
 * the per-install salt and calls [redact] on every write.
 *
 * Two kinds of personal data reach the log:
 *
 * 1. **Phone numbers**, which are simply removed. They are worthless for reading a log anyway.
 * 2. **Contact names**, which arrive constantly and were not redacted at all until now, because a
 *    recording's `displayName` *is* its filename and the filename carries the contact:
 *    `20260825_130207.420+0300_out_Some Person.ogg`. Dozens of log sites interpolate that name
 *    ("Transcribed $displayName", "Could not draw $displayName", …), so redacting at the call sites
 *    was never going to hold — the next person to log a display name would reintroduce the leak.
 *    They are **pseudonymised** rather than removed: the name becomes a short stable token, so the
 *    same contact reads as the same token on every line, in every recording, and across separate
 *    reports from the same user — which is exactly the correlation a bug report needs — while the
 *    name itself never leaves the device.
 */
object LogRedaction {

    /** What a phone number becomes. Unchanged from the original rule, so old logs still read alike. */
    private const val PHONE_PLACEHOLDER = "[PHONE_REDACTED]"

    /**
     * How many hex digits of the salted digest a contact token carries.
     *
     * Four (16 bits) is a deliberate readability/collision trade. Two distinct contacts collide with
     * probability n(n-1)/2/65536 over the n distinct people in one log window: ~1.5% at 40 people,
     * ~7% at 100. A collision costs a moment's confusion when reading, never a leak, and the log
     * window is a few days of calls rather than a whole contact list. Widen this constant if that
     * ever bites; nothing else needs to change, and only token *width* changes with it.
     */
    private const val TOKEN_HEX_DIGITS = 4

    /** Longest run accepted as the contact field of a filename. Generous; caps regex backtracking. */
    private const val MAX_NAME_RUN = 120

    /**
     * The original, unchanged phone-number rule.
     *
     * Note it is *not* applied to the timestamp of a recording filename — see [redact]. On its own it
     * happily eats one: `20260825_130207.420+0300` used to come out as
     * `[PHONE_REDACTED]_[PHONE_REDACTED]+0300`, which is why two log lines about two different calls
     * were previously indistinguishable except by the contact name they leaked.
     */
    /**
     * Units we log numbers in. A number followed by one of these is a measurement, not a person.
     *
     * Needed because the rule below cannot tell `4685000 frames` from an Israeli mobile written bare
     * as `0544557278` — both are unbroken digit runs of the same length, and no pattern separates
     * them. What *does* separate them is the word we ourselves put next to it.
     *
     * Found by reading a real log rather than by reasoning: `HandoffEncoder finished:
     * [PHONE_REDACTED] frames` had been shipping for months. That line exists for no purpose other
     * than reporting how much audio was captured, and the number was the entire content of it.
     *
     * Only 7-digit-and-longer runs ever reached the rule, which is why `48000 Hz` and `(97627 ms)`
     * were never affected and the damage stayed invisible. Keep this list to units *we* emit; it is
     * an exception to a privacy rule, so a loose entry here is a leak rather than an inconvenience.
     */
    private const val MEASUREMENT_UNITS = "frames|samples|bytes|ms|Hz|kHz|KB|MB|GB"

    private val PHONE_REGEX = Regex(
        "(?<!\\d)" +                      // Negative Lookbehind: Don't start in the middle of another number
            "(?:\\+?\\d{1,3}[-.\\s]?)?" + // Optional Country Code (e.g., +1 or 33)
            "(?:\\(\\d{1,4}\\)|\\d{1,4})" + // Area code (with or without parentheses)
            "[-.\\s]?\\d{3,4}" +          // Prefix
            "[-.\\s]?\\d{3,4}" +          // Line number
            "(?!\\d)" +                   // Negative Lookahead: Don't end in the middle of another number
            "(?!\\s*(?:$MEASUREMENT_UNITS)\\b)" // ...and it is a measurement, not a number to hide
    )

    /**
     * A recording filename, split into the parts that identify a call and the one part that
     * identifies a *person*.
     *
     * The grammar is the one both `RecordingFileNameFormatter` and `VoipRecordingCoordinator` emit
     * and `RecordingsRepository.parseName` reads back:
     *
     *   `{date}[_in|_out|_voip[-App]][_{contact or number}].{ogg|m4a}`
     *
     * with `{date}` = `yyyyMMdd_HHmmss.SSSZ`. Group 1 (the timestamp), group 2 (direction, or the
     * VoIP marker with the app it was made in) and group 4 (the extension) name nobody and are kept
     * verbatim — they are the whole reason a redacted log is still readable. Only group 3 is replaced.
     *
     * The direction marker requires a `_` or `.` after it so a contact called "info desk" is not read
     * as an incoming call, and the contact run excludes path separators and newlines so a match can
     * never run off the end of a filename into the rest of the line.
     *
     * **Percent-encoding is accepted for the timestamp's UTC offset**, because the single most common
     * way a filename reaches the log is not as a filename at all — it is inside a SAF document URI:
     *
     *   `content://…/document/primary%3ARecordings%2F20260816_120000.000%2B0300_in_John%20Doe.ogg`
     *
     * Fifteen log sites print one of those (playback errors, delete failures, decode failures, …). The
     * name is right there with its spaces as `%20`; only the `+` of the offset is encoded differently,
     * so accepting `%2B` is the whole difference between covering those sites and not. The `%20`s in
     * the contact run need no special handling — they are just characters in the run.
     */
    private val RECORDING_NAME_REGEX = Regex(
        "(\\d{8}_\\d{6}\\.\\d{3}(?:[+-]|%2[BbDd])\\d{4})" +             // 1: {date}, raw or URI-encoded
            "((?:_(?:in|out|voip(?:-[^_\\s.]+)?)(?=[_.]))?)" +          // 2: direction / voip[-App]
            "(?:_([^/\\\\\\n]{1,$MAX_NAME_RUN}?))?" +                   // 3: contact name or number
            "(\\.(?:ogg|m4a))"                                          // 4: container extension
    )

    /** A token this class has already produced. Matching it again must be a no-op — see [redact]. */
    private val CONTACT_TOKEN_REGEX = Regex("\\[C:[0-9a-f]+]")

    /**
     * Cheap pre-filter for [RECORDING_NAME_REGEX].
     *
     * [redact] runs on **every** log write, so the second regex has to earn its place. Every recording
     * filename contains the `_` inside `yyyyMMdd_HHmmss`, so a message with no underscore cannot
     * possibly contain one. That is a single linear character scan with no regex machinery, and it
     * skips the majority of lines (prose, `->` state changes, exception messages).
     */
    private fun mayContainRecordingName(message: String) = message.indexOf('_') >= 0

    /**
     * Redacts a single log line: phone numbers out, contact names pseudonymised.
     *
     * **Order matters, and it is not "one rule then the other".** The phone rule would eat the
     * timestamp of every filename it saw, and the name rule would then be pseudonymising
     * `[PHONE_REDACTED]`. So the line is walked once: the spans that are recording filenames are
     * rewritten by [safeRecordingName], and the phone rule is applied to everything *between* them.
     * Inside a filename the number case is still handled — a call with no contact carries the number
     * in the contact slot, and that slot is checked against the phone rule explicitly.
     *
     * Idempotent. It has to be: a daemon line is redacted in the daemon process and again in the app
     * at export time, and logcat lines collected for the report were already redacted when the app
     * wrote them. Re-tokenising an existing token would give the same contact two different tokens in
     * one report and destroy the correlation this exists to provide.
     *
     * @param salt this install's random salt, from `AppPreferences.getLogPseudonymSalt`.
     */
    fun redact(message: String, salt: String): String = redactPeople(redactApiKeys(message), salt)

    /**
     * API keys for cloud transcription (issue #48), in case one ever reaches a line: Groq's `gsk_…`,
     * OpenRouter's and OpenAI's `sk-…`, and anything after `Bearer `. Nothing logs a key on purpose —
     * this is the net under that rule, so a mistake costs a placeholder rather than someone's account.
     *
     * First, so the phone rule never sees a key's digits. Idempotent: the placeholder matches nothing.
     */
    fun redactApiKeys(message: String): String =
        if (!message.contains("sk") && !message.contains("Bearer", ignoreCase = true)) message
        else API_KEY_REGEX.replace(message, KEY_PLACEHOLDER)

    private const val KEY_PLACEHOLDER = "[KEY_REDACTED]"

    private val API_KEY_REGEX = Regex(
        "(?i)(?:\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}|\\b(?:gsk_|sk-or-v1-|sk-proj-|sk-)[A-Za-z0-9_-]{12,})"
    )

    private fun redactPeople(message: String, salt: String): String {
        if (!mayContainRecordingName(message)) return PHONE_REGEX.replace(message, PHONE_PLACEHOLDER)

        val out = StringBuilder(message.length + 16)
        var cursor = 0
        // The two shapes never overlap (their dates differ), but an earlier match always wins.
        val matches = (RECORDING_NAME_REGEX.findAll(message).map { it to false } +
            HONOR_NAME_REGEX.findAll(message).map { it to true })
            .sortedBy { (match, _) -> match.range.first }
        for ((match, isHonor) in matches) {
            if (match.range.first < cursor) continue
            val prefixStart = if (isHonor) nameStart(message, match.range.first, cursor)
                else personPrefixStart(message, match, cursor)
            out.append(PHONE_REGEX.replace(message.substring(cursor, prefixStart), PHONE_PLACEHOLDER))
            if (isHonor) {
                out.append(safeHonorName(message.substring(prefixStart, match.range.first), match, salt))
            } else {
                if (prefixStart < match.range.first) {
                    // The '_' that joins the prefix to the date is kept; only the fields before it change.
                    out.append(safePersonPrefix(message.substring(prefixStart, match.range.first - 1), salt)).append('_')
                }
                out.append(safeRecordingName(match, salt))
            }
            cursor = match.range.last + 1
        }
        out.append(PHONE_REGEX.replace(message.substring(cursor), PHONE_PLACEHOLDER))
        return out.toString()
    }

    /**
     * Rebuilds an Honor-preset name (#56): the contact becomes a token (or [PHONE_PLACEHOLDER] for an unsaved
     * caller, whose number stands in for the name), the number becomes [PHONE_PLACEHOLDER], and the `@` — plain
     * or `%40` — the time and the extension are kept.
     */
    private fun safeHonorName(name: String, match: MatchResult, salt: String): String {
        val (at, number, stamp, extension) = match.destructured
        val decodedName = percentDecode(name).trim()
        val safeName = when {
            name.isEmpty() || CONTACT_TOKEN_REGEX.matches(name) || name == PHONE_PLACEHOLDER -> name
            PHONE_REGEX.matches(decodedName) -> PHONE_PLACEHOLDER
            else -> contactToken(name, salt)
        }
        val safeNumber = if (number.isEmpty()) "" else PHONE_PLACEHOLDER
        return safeName + at + safeNumber + "_" + stamp + extension
    }

    /**
     * Where the person fields of a contact-first filename start, or the date's own index when there are none.
     *
     * The presets added in 2.4.5 (`{contact_name}_{phone_number}_{date}`, `{number}_{contact}_{date}`,
     * `{contact}_{date}`, `{number}_{date}`) put the person BEFORE the date and nothing after it, so
     * [RECORDING_NAME_REGEX] matches only the bare `{date}.ext` tail. That shape, joined by a `_` to
     * what precedes it, is the signal; a date-first name always has a marker or a contact after its date.
     *
     * A name may hold spaces, so nothing inside the line says where it begins. We walk back to the
     * nearest character a log line puts around a name — a quote, a path or URI separator, `:` `=` `(`
     * — or to the start of the line. A bare `Transcribed John Doe_…` therefore takes "Transcribed"
     * with it: losing a word of our own prose is the price; printing a name is not an option.
     * Tokens this class already wrote (`[C:…]`, `[PHONE_REDACTED]`) are stepped over whole, so a
     * second pass finds the same span and leaves it alone.
     */
    private fun personPrefixStart(message: String, match: MatchResult, cursor: Int): Int {
        val dateStart = match.range.first
        val (_, marker, who, _) = match.destructured
        if (marker.isNotEmpty() || who.isNotEmpty()) return dateStart
        if (dateStart - 1 <= cursor || message[dateStart - 1] != '_') return dateStart

        val start = nameStart(message, dateStart - 1, cursor)
        return if (start < dateStart - 1) start else dateStart
    }

    /**
     * Walks back from [joinIndex] (the `_` or `@` that ties a name to the rest of a filename) to where the name
     * begins: the nearest [NAME_BOUNDARY_CHARS], encoded URI separator, [cursor] or [MAX_NAME_RUN] characters
     * back. Returns [joinIndex] itself when there is no name. Redaction tokens are stepped over whole.
     */
    private fun nameStart(message: String, joinIndex: Int, cursor: Int): Int {
        val floor = maxOf(cursor, joinIndex - MAX_NAME_RUN)
        var i = joinIndex
        while (i > floor) {
            val c = message[i - 1]
            if (c == ']') {
                val tokenStart = REDACTED_TOKEN_SUFFIX.find(message.substring(floor, i))?.range?.first
                if (tokenStart != null) {
                    i = floor + tokenStart
                    continue
                }
            }
            if (c in NAME_BOUNDARY_CHARS || endsWithEncodedSeparator(message, i)) break
            i--
        }
        return i
    }

    /** True when `%2F` or `%3A` (a SAF URI's `/` and `:`) ends just before [end]. */
    private fun endsWithEncodedSeparator(message: String, end: Int): Boolean =
        end >= 3 && message.regionMatches(end - 3, "%2F", 0, 3, ignoreCase = true) ||
            end >= 3 && message.regionMatches(end - 3, "%3A", 0, 3, ignoreCase = true)

    /**
     * Rewrites the `_`-joined fields before a contact-first name's date: a number becomes
     * [PHONE_PLACEHOLDER], anything else a contact token, and an empty field (no contact for that call)
     * stays empty so the shape of the name survives.
     */
    private fun safePersonPrefix(prefix: String, salt: String): String =
        PREFIX_FIELD_REGEX.findAll(prefix).joinToString("") { field ->
            val value = field.value
            val decoded = percentDecode(value).trim()
            when {
                value == "_" -> value
                CONTACT_TOKEN_REGEX.matches(value) || value == PHONE_PLACEHOLDER -> value
                decoded.isEmpty() -> value
                PHONE_REGEX.matches(decoded) -> PHONE_PLACEHOLDER
                else -> contactToken(value, salt)
            }
        }

    /** Characters a log line puts around a filename or URI; a contact-first name never runs past one. */
    private val NAME_BOUNDARY_CHARS = setOf('\'', '"', '`', '/', '\\', '\n', '\r', '\t', ':', '=', '(', '<', '>', ',', ';')

    /** A redaction token ending the text, so the backward walk can step over it in one move. */
    private val REDACTED_TOKEN_SUFFIX = Regex("(?:\\[C:[0-9a-f]+]|\\[PHONE_REDACTED])$")

    /**
     * The tail of an Honor-preset name (#56), from its `@` on: `@{number}_{yyyyMMddHHmmss}.{ext}`. The `@` and
     * the number's `+` may arrive percent-encoded inside a SAF URI, and the number may already be redacted.
     * The name in front is found by [nameStart], since nothing in it marks where it begins.
     */
    private val HONOR_NAME_REGEX = Regex(
        "(@|%40)" +                                          // 1: the separator, raw or URI-encoded
            "((?:(?:\\+|%2[Bb])?\\d+)|\\[PHONE_REDACTED])?" +   // 2: the number, if any
            "_(\\d{14})" +                                     // 3: yyyyMMddHHmmss
            "(\\.(?:ogg|m4a))"                                 // 4: container extension
    )

    /** One field of a contact-first prefix: an existing token, a `_`, or a run of anything else. */
    private val PREFIX_FIELD_REGEX = Regex("\\[C:[0-9a-f]+]|\\[PHONE_REDACTED]|_|[^_\\[]+|\\[")

    /** Rebuilds one filename match with only its person-identifying field replaced. */
    private fun safeRecordingName(match: MatchResult, salt: String): String {
        val (stamp, marker, who, extension) = match.destructured
        val safeWho = when {
            who.isEmpty() -> ""
            // Already redacted — a second pass over the same line must not move the token.
            CONTACT_TOKEN_REGEX.matches(who) || who == PHONE_PLACEHOLDER -> who
            // No contact for this call, so the filename carries the number instead. Still a number.
            PHONE_REGEX.matches(who.trim()) -> PHONE_PLACEHOLDER
            else -> contactToken(who, salt)
        }
        return stamp + marker + (if (safeWho.isEmpty()) "" else "_$safeWho") + extension
    }

    /**
     * The stable pseudonym for one contact name: `[C:7f3a]`.
     *
     * Salted, and that is the whole point. The space of contact names is tiny — a bare hash of "Mum"
     * or of any name in a phone book is reversed by a dictionary in seconds, which would make the
     * token no better than printing the name. Mixing in a random per-install value makes the tokens
     * stable for one user (so their own reports correlate with each other) and meaningless to anyone
     * comparing across users, which is the property a public issue tracker needs.
     *
     * **If the salt is lost the tokens change.** That is acceptable: the salt lives in preferences and
     * is lost only on uninstall/clear-data, which also throws away the log the tokens appeared in.
     * Old tokens simply stop matching new ones; nothing breaks and nothing leaks.
     *
     * Names are percent-decoded, trimmed and lower-cased first so that one person stays one token: the
     * same contact reaches the log as `John Doe` from a filename and as `John%20Doe` from a SAF URI,
     * and two tokens for one person would defeat the point of having a token at all.
     */
    fun contactToken(name: String, salt: String): String {
        val normalised = percentDecode(name).trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
            // NUL separator, not a space or a dash: both halves can contain either, and a
            // separator that can occur in the name would let two different (salt, name) pairs
            // hash to the same token.
            .digest("$salt\u0000$normalised".toByteArray(Charsets.UTF_8))
        val hex = buildString(TOKEN_HEX_DIGITS) {
            for (i in 0 until (TOKEN_HEX_DIGITS + 1) / 2) {
                val b = digest[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
        return "[C:${hex.take(TOKEN_HEX_DIGITS)}]"
    }

    private const val HEX = "0123456789abcdef"

    /**
     * Turns `%XX` escapes back into the characters they stand for, so the URI form of a name hashes to
     * the same token as the plain form.
     *
     * Hand-rolled rather than `URLDecoder`, which also turns `+` into a space (wrong outside a form
     * body) and throws on a stray `%`. Escapes are collected as *bytes* before decoding, because a
     * Hebrew name arrives as a run of them (`%D7%99%D7%A6…`) and decoding one at a time would produce
     * mojibake and, worse, a token that changed with the encoding. Anything malformed is passed
     * through untouched: a name that fails to decode must still be replaced, never printed.
     */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val hi = if (i + 2 < value.length && value[i] == '%') hexValue(value[i + 1]) else -1
            val lo = if (hi >= 0) hexValue(value[i + 2]) else -1
            if (lo >= 0) {
                bytes.write((hi shl 4) or lo)
                i += 3
                continue
            }
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
            out.append(value[i])
            i++
        }
        if (bytes.size() > 0) out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        return out.toString()
    }

    private fun hexValue(c: Char): Int = HEX.indexOf(c.lowercaseChar())
}
