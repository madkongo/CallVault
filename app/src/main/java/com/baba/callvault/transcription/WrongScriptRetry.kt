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

/**
 * Notices a transcript written in the wrong alphabet, and decodes the recording again another way.
 *
 * **The language pin is a strong hint to whisper, not a guarantee.** Measured on 2026-09-20: Hebrew
 * pinned, `lang=he` in the log, no prompt, and a Hebrew test call came back as "God, God, God, God, God is
 * 1 plus 12." — on the OP12 and, with the same file, on the OP9. A day of elimination found nothing
 * broken: the setting is applied, the contact-name prompt was not sent, the app's resampler and
 * MediaCodec's Opus decode both produce the same audio ffmpeg does (levels equal, correlation 0.988), and
 * the same audio as a WAV transcribes in Hebrew on the same phone. The desktop never reproduces it at all.
 * The decode is simply low-margin on a short, loanword-heavy clip, and on ARM it tips over.
 *
 * So the fix is not in the pipeline. It is here: this failure is the one failure of whisper's that can be
 * seen without understanding a word — **a non-Latin language pinned, and a transcript in Latin letters.**
 *
 * Not judged: Latin-script languages (they share an alphabet, so a script says nothing) and auto-detect
 * (there is no pin to contradict).
 */
object WrongScriptRetry {

    private const val TAG = "CV:WrongScriptRetry"

    /**
     * Fewest letters worth judging. Below this a transcript is "OK." or a phone number, and a verdict on
     * it would be noise.
     */
    private const val MIN_LETTERS = 12

    /**
     * How little of a transcript may be in the pinned language's script before it counts as the wrong one.
     * Real Hebrew calls are full of English — brand names, "plus", "OK" — so the bar is far below half:
     * the measured failure had none at all, and the measured healthy transcripts were well over half.
     */
    private const val MIN_EXPECTED_FRACTION = 0.2

    /**
     * Longest recording that is decoded again. A retry costs the whole decode a second time, and short
     * clips are where whisper has least to go on; an hour of English under a Hebrew pin is a wrong
     * setting, not a knife-edge, and tripling its cost would help nobody.
     */
    const val MAX_RETRY_AUDIO_MS = 3 * 60_000L

    /**
     * The other ways to decode, in the order they are tried. Without the VAD first: it changes what audio
     * whisper sees, which is what tipped the measured clip, and costs nothing extra on a short recording.
     * Then beam search — rejected as a DEFAULT for what it did to a long call (see [DecodeSettings]), which
     * is not an argument against it on a clip this short that has already failed.
     *
     * 📐 The order is reasoned, not measured: the desktop cannot reproduce the failure, and the on-device
     * benchmark could not be made to finish. What a phone actually recovers with is logged every time.
     */
    val FALLBACKS: List<DecodeSettings> = listOf(
        DecodeSettings(useVad = false),
        DecodeSettings(beamSize = 5),
        DecodeSettings(beamSize = 5, useVad = false),
    )

    fun isWrongScript(segments: List<TranscriptSegment>, language: String?): Boolean {
        if (language == null || !LanguageScript.isNonLatin(language)) return false
        val expected = LanguageScript.of(language)

        val letters = segments.asSequence().flatMap { it.text.codePoints().toArray().asSequence() }
            .filter { Character.isLetter(it) }
            .toList()
        if (letters.size < MIN_LETTERS) return false

        val inExpected = letters.count { Character.UnicodeScript.of(it) == expected }
        return inExpected < MIN_EXPECTED_FRACTION * letters.size
    }

    /**
     * Whether the transcript carries letters from a THIRD alphabet — neither the pinned language's nor
     * Latin. Latin inside Hebrew is a brand name; Korean inside Hebrew is whisper coming apart. Measured
     * on the OP12: a fallback that recovered Hebrew wrote "זה 1 Behindração שת달ים" for "זה 1 פלוס 12".
     */
    fun hasStrayScript(segments: List<TranscriptSegment>, language: String?): Boolean {
        if (language == null || !LanguageScript.isNonLatin(language)) return false
        val expected = LanguageScript.of(language)
        return segments.any { segment ->
            segment.text.codePoints().anyMatch { point ->
                Character.isLetter(point) && Character.UnicodeScript.of(point).let {
                    it != expected && it != Character.UnicodeScript.LATIN && it != Character.UnicodeScript.COMMON
                }
            }
        }
    }

    fun shouldRetry(segments: List<TranscriptSegment>, language: String?, audioMs: Long): Boolean =
        // A KNOWN length inside the limit. Unknown is not "short": a container that declares no duration
        // can be an hour long, and tripling that on a guess is the one way this could make a run worse.
        audioMs in 1..MAX_RETRY_AUDIO_MS && isWrongScript(segments, language)

    /**
     * [first] decoded again through each of [FALLBACKS] until one comes back in the right script.
     *
     * @return that transcript, or [first] when none does — never the last fallback's, which would trade
     *   a bad transcript for one made with worse settings.
     */
    suspend fun recover(
        first: List<TranscriptSegment>,
        language: String?,
        decode: suspend (DecodeSettings) -> List<TranscriptSegment>,
    ): List<TranscriptSegment> {
        // The first CLEAN recovery wins. One that is in the right alphabet but carries a third one is
        // kept in hand and only used if nothing clean turns up: it still beats the wrong language.
        var dirty: List<TranscriptSegment>? = null
        FALLBACKS.forEach { settings ->
            val again = runCatching { decode(settings) }
                .onFailure {
                    // A stop is not a failed fallback. Swallowed, the run would carry on decoding a
                    // recording the user has just told it to leave alone.
                    if (it is CancellationException) throw it
                    AppLogger.w(TAG, "Retry with $settings failed: ${it.message}")
                }
                .getOrNull() ?: return@forEach
            when {
                again.isEmpty() || isWrongScript(again, language) ->
                    AppLogger.i(TAG, "Still the wrong script with $settings")
                hasStrayScript(again, language) -> {
                    AppLogger.i(TAG, "Right script but a stray alphabet in it with $settings; looking for a clean one")
                    dirty = dirty ?: again
                }
                else -> {
                    AppLogger.i(TAG, "Recovered the pinned language ($language) with $settings")
                    return again
                }
            }
        }
        dirty?.let {
            AppLogger.w(TAG, "No clean recovery of $language; keeping the first one in the right script")
            return it
        }
        AppLogger.w(TAG, "No fallback recovered $language; keeping the first transcript")
        return first
    }
}
