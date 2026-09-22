/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * The words to expect, handed to whisper before it decodes a call.
 *
 * Exactly one thing goes in here: **the contact's name**. A name spelled differently in every
 * transcript is a name that search will never find, and whisper has no other way to know it.
 *
 * ## Why nothing else does
 *
 * Whisper is general-purpose, so anything unusual comes out as whatever it sounds like — measured on
 * a real Hebrew call, the phone model "OnePlus 9" was transcribed `1 + 9`, arithmetic rather than a
 * name. Two attempts to fix that from here were made, and both are gone:
 *
 *  1. **A glossary the user types.** Rejected outright, and rightly: the words that need help are
 *     exactly the ones you never think to list, so a box nobody fills in helps nobody.
 *  2. **A built-in list of common names** (`OnePlus, Samsung, iPhone, …`). This *worked* — it turned
 *     `1 plus 10` into `OnePlus 10` on the failing call, verified on the phone — and was removed
 *     anyway, because of what it cost. Measured on a real call, same audio and model, three
 *     identical runs each:
 *
 * | audio | segments, no prompt | with the contact's name | with the name list |
 * |---|---|---|---|
 * | 30s | 9 | 12 | **3** |
 * | 45s | 19 | 21 | **4** |
 *
 * A long prompt reads to whisper as text the call is continuing from, and it answers with a few
 * enormous segments instead of many small ones. **Segments are what speaker labels are made of** — a
 * line both people share belongs to neither — so the name list bought correct brand spelling by
 * taking "You" and the contact's name off the transcript altogether. A bad trade, and one caught
 * only because the maintainer noticed labels vanish from a call that had had them.
 *
 * The contact's name is short enough not to do this, which is the whole reason it survives.
 *
 * **Anything added here must be measured against segment counts on a real call**, not just read to
 * see whether the words came out right. The damage is invisible in the text itself.
 *
 * ## And why the name is sometimes left out too
 *
 * Whisper follows the language of the words it is primed with over the language it is *told*.
 * Measured on the OP12 on 2026-09-20: Hebrew pinned, the log said `lang=he`, and a Hebrew call came
 * out in English — "God, God, God is 1 plus 12" for "בדיקה, בדיקה" — because the contact was saved as
 * `Feroza`. The same phone with a contact saved in Hebrew transcribed correctly. So a name in another
 * script than the pinned language is not sent: it cannot help the spelling of a transcript written in
 * a different alphabet, and it can silently turn the language pin off.
 */
object TranscriptionPrompt {

    /**
     * Longest prompt handed over.
     *
     * Well under whisper's own limit, deliberately. The ceiling is where recitation starts — whisper
     * writes an over-long prompt back out inside the transcript — and the segment collapse above
     * begins well before that, so being anywhere near the limit buys nothing.
     */
    const val MAX_CHARS = 220

    /**
     * The prompt for one recording, or null when there is nothing worth saying.
     *
     * @param contactName who the call is with. The one term certain to be spoken, and the one whose
     *   spelling should not drift between transcripts. Ignored when it is really a phone number.
     * @param language the language whisper is pinned to, or null under auto-detect. A name in another
     *   script than this is left out — see the class comment.
     */
    fun build(contactName: String?, language: String?): String? {
        val name = contactName?.trim()?.takeIf { it.isNotEmpty() && !isPhoneNumber(it) } ?: return null
        if (!isWrittenIn(name, language)) return null

        return "$name.".take(MAX_CHARS)
    }

    /**
     * Whether [name] is a phone number wearing a name's place.
     *
     * An unsaved contact falls back to their number, and feeding digits to whisper immediately
     * before a call in which numbers are spoken invites it to write those digits down.
     */
    /**
     * Whether every letter of [name] belongs to the script [language] is written in.
     *
     * Every letter rather than most of them: half a name in the wrong script still primes whisper with
     * the wrong language. Digits, spaces and punctuation are no script's, so `פרוזה 2` is Hebrew.
     *
     * True under auto-detect (a null [language]) — there is no pin to contradict, and what a prompt
     * does to auto-detect has not been measured, so that case is left exactly as it was.
     */
    private fun isWrittenIn(name: String, language: String?): Boolean {
        if (language == null) return true
        val expected = LanguageScript.of(language)
        return name.codePoints()
            .filter { Character.isLetter(it) }
            .allMatch { Character.UnicodeScript.of(it) == expected }
    }

    private fun isPhoneNumber(name: String): Boolean =
        name.count { it.isDigit() } >= name.count { !it.isWhitespace() } / 2
}
