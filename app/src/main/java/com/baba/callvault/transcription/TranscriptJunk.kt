/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Notices a finished transcript that is not words: one token said over and over ("foreign foreign
 * foreign ..."), or nothing but whisper's non-speech tags ("[Music]", "(speaking in foreign language)").
 *
 * **Why this exists (BabApps item 139).** Whisper fed audio it cannot read as the pinned language, or
 * silence, does not say so: it fills the call with one filler word. A Hindi call transcribed under an
 * English pin came back as "foreign" in both segments and was saved as a finished transcript, with no hint
 * that the language setting was the problem. [WrongScriptRetry] cannot see it: an English pin is Latin, so
 * a Latin transcript is "in the right script".
 *
 * Deliberately narrow: it only judges text that is one repeated token or only tags, never "looks odd".
 * Real replies stay clear of it by construction — "OK." and "Yes yes, bye" are far below [MIN_WORDS].
 */
object TranscriptJunk {

    private const val TAG = "CV:TranscriptJunk"

    /** Fewest words worth judging as a repeat. Below this a transcript is a short reply, not a loop. */
    const val MIN_WORDS = 8

    /** How much of the words the commonest one must be before the transcript counts as a loop. */
    const val MIN_SHARE = 0.9

    /**
     * Tags whisper writes in square or round brackets, or between asterisks or music notes, for audio it
     * took to be no speech. Removed before the words are counted.
     */
    private val TAGS = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*|♪[^♪]*♪|♪""")

    /** Bare words whisper writes in place of speech it cannot read; the bracketed forms are [TAGS]. */
    private val BARE_FILLER = setOf("foreign")

    private val NOT_A_WORD = Regex("""[^\p{L}\p{N}\p{M}]+""")

    enum class Kind { REPEATED_TOKEN, TAGS_ONLY }

    /** Why a transcript is junk, and the token (or tag) that gave it away. */
    data class Found(val kind: Kind, val token: String)

    /** The thrown form, so the runner's failure path can tell this ending from any other. */
    class JunkTranscriptException(val found: Found) :
        Exception("The transcript is not words (${found.kind}: '${found.token}'): wrong language, or empty audio")

    /** Why [segments] are junk, or null when they are a real transcript (or empty, which is not this guard's case). */
    fun find(segments: List<TranscriptSegment>): Found? {
        val text = segments.joinToString(" ") { it.text }
        if (text.isBlank()) return null

        val spoken = TAGS.replace(text, " ")
        val words = spoken.lowercase(Locale.ROOT).split(NOT_A_WORD).filter { it.isNotEmpty() }

        if (words.isEmpty()) {
            // Something was written, and all of it was tags: the first one names it.
            val tag = TAGS.find(text)?.value?.trim() ?: text.trim()
            return Found(Kind.TAGS_ONLY, tag.take(40))
        }
        if (words.all { it in BARE_FILLER }) return Found(Kind.REPEATED_TOKEN, words.first())

        if (words.size >= MIN_WORDS) {
            val top = words.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
            if (top.value >= MIN_SHARE * words.size) return Found(Kind.REPEATED_TOKEN, top.key.take(40))
        }
        return null
    }

    /**
     * The first of [WrongScriptRetry.FALLBACKS] that decodes [language] into a real transcript, or null.
     *
     * The same fallbacks the wrong-script retry uses, because they are the proven way to change what
     * whisper sees. "Real" means not junk, not empty and not in the wrong script. A stop is never
     * swallowed: a cancelled run must not carry on decoding.
     */
    suspend fun recover(
        language: String?,
        decode: suspend (DecodeSettings) -> List<TranscriptSegment>,
    ): List<TranscriptSegment>? {
        WrongScriptRetry.FALLBACKS.forEach { settings ->
            val again = runCatching { decode(settings) }
                .onFailure {
                    if (it is CancellationException) throw it
                    AppLogger.w(TAG, "Retry with $settings failed: ${it.message}")
                }
                .getOrNull() ?: return@forEach
            val stillJunk = find(again)
            when {
                again.isEmpty() -> AppLogger.i(TAG, "Retry with $settings gave nothing")
                stillJunk != null -> AppLogger.i(TAG, "Retry with $settings is junk too (${stillJunk.kind}: '${stillJunk.token}')")
                WrongScriptRetry.isWrongScript(again, language) -> AppLogger.i(TAG, "Retry with $settings is in the wrong script")
                else -> {
                    AppLogger.i(TAG, "Retry with $settings gave a real transcript")
                    return again
                }
            }
        }
        return null
    }
}
