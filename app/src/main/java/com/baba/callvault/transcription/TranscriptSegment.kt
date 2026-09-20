/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * One contiguous piece of recognised speech, timed relative to the start of the recording.
 *
 * Segments rather than one blob of text for two reasons: a transcript view needs to seek playback to
 * the line the user tapped, and a long job has to be resumable — persisting completed segments as
 * they arrive is what stops a 65-minute transcription restarting from zero.
 */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /**
     * Where this segment could be cut, offered by the engine and settled by the runner: the same words
     * as [text], divided at every pause the VAD found. Empty when there was no pause inside it, which is
     * nearly always. See [SpeakerSeamSplit] — the engine cannot decide, because it decodes before the
     * speaker turns exist.
     */
    val parts: List<TranscriptSegment> = emptyList(),
)
