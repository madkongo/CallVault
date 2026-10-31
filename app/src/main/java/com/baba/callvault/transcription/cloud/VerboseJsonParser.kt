/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import com.baba.callvault.transcription.TranscriptSegment
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads an OpenAI-style `verbose_json` transcription: `segments[]` of `{start, end, text}` in seconds.
 *
 * Timings are not optional here. A transcript row is tapped to seek, and the speaker labeller lines
 * rows up against the energy turns by time, so text with no times is refused rather than stored.
 */
object VerboseJsonParser {

    private const val MS_PER_SECOND = 1000.0

    /**
     * What came back.
     *
     * @param hasSegmentsField whether the response carried a `segments` array at all — the Test
     *   button's question, since one second of silence rightly yields an empty one.
     */
    data class Result(val segments: List<TranscriptSegment>, val hasSegmentsField: Boolean, val text: String)

    /** Parses without judging; throws [JSONException] on a body that is not a JSON object. */
    fun read(body: String): Result {
        val json = JSONObject(body)
        val text = json.optString("text", "").trim()
        val array: JSONArray = json.optJSONArray("segments") ?: return Result(emptyList(), false, text)
        val segments = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val segmentText = item.optString("text", "").trim()
            if (segmentText.isEmpty()) return@mapNotNull null
            val start = item.optDouble("start", Double.NaN)
            val end = item.optDouble("end", Double.NaN)
            if (start.isNaN() || end.isNaN() || start < 0) return@mapNotNull null
            val startMs = (start * MS_PER_SECOND).toLong()
            TranscriptSegment(
                startMs = startMs,
                endMs = maxOf(startMs, (end * MS_PER_SECOND).toLong()),
                text = segmentText,
            )
        }
        return Result(segments, hasSegmentsField = true, text = text)
    }

    /**
     * The segments of one chunk, times relative to that chunk.
     *
     * @throws CloudError NO_SEGMENTS when there are words but no timed segments for them.
     *   Silence (no words at all) is an empty list, not an error.
     */
    fun segments(body: String, service: String): List<TranscriptSegment> {
        val result = try {
            read(body)
        } catch (e: JSONException) {
            throw CloudError(CloudError.Kind.REJECTED, "$service sent an answer that is not JSON.")
        }
        if (result.segments.isNotEmpty()) return result.segments
        if (!result.hasSegmentsField || result.text.isNotEmpty()) throw CloudError.noSegments(service)
        return emptyList()
    }
}
