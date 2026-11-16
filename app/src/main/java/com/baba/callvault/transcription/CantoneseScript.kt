/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Writes a pinned-Cantonese transcript in Traditional characters, the way Hong Kong writes them
 * (BabApps item 117).
 *
 * Whisper's Cantonese output comes back in Simplified characters most of the time — measured with
 * `whisper-cli -l yue` on large-v3-turbo-q8_0 — while Cantonese readers expect Traditional. A Traditional
 * initial prompt was tried and rejected: it turned the characters Traditional but moved the wording to
 * Mandarin (喝茶, 改了) and garbled the punctuation. So there is no prompt; this converts the finished text.
 *
 * Since BabApps item 125 the table is OpenCC's full data, see [ChineseScript]; this keeps the Cantonese
 * entry point (系 is 係, 什麼 is 甚麼) the callers already use.
 */
object CantoneseScript {

    private val CONVERSION = ChineseScript.Conversion(ChineseScript.Variant.HK, cantonese = true)

    /** Converts Simplified Chinese characters in [text] to Hong Kong Traditional, Cantonese wording. */
    fun toTraditionalHk(text: String): String = ChineseScript.convert(text, CONVERSION)

    /** [toTraditionalHk] on a segment and the lines it can be cut into. */
    fun toTraditionalHk(segment: TranscriptSegment): TranscriptSegment = ChineseScript.convert(segment, CONVERSION)
}
