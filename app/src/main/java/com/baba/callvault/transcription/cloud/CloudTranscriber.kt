/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import android.content.Context
import android.net.Uri
import com.baba.callvault.server.speakers.OfflineSpeakerLabeller
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.transcription.ChunkDecode
import com.baba.callvault.transcription.ChunkPlan
import com.baba.callvault.transcription.TranscriptSegment
import com.baba.callvault.transcription.Transcriber
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transcribes a recording with a cloud service, one [ChunkPlan] pass at a time (issue #48).
 *
 * The audio is still decoded here, on the phone, for two reasons: the speaker labeller reads who spoke
 * from the raw channels of that decode, exactly as on the on-device path, and each pass goes up as a
 * small 16 kHz mono WAV rather than as the whole recording. Each pass's segments are moved onto the
 * call's timeline with the same [ChunkPlan.stitch] the on-device path uses.
 *
 * None of whisper.cpp's local repairs apply — no VAD, no SpeechGapSnap, no wrong-script retry — those
 * fix artefacts of the on-device decoder, and the runner skips them for a cloud engine id.
 *
 * Stop is shared with the on-device engine: [TranscriptionEngine.requestAbort] sets the flag this reads
 * between passes, so the one Stop button reaches both.
 */
class CloudTranscriber(
    private val endpoint: CloudEndpoint,
    private val apiKey: String?,
    private val client: CloudTranscriptionClient = CloudTranscriptionClient(),
) {

    /** As a [Transcriber] for the runner. `modelPath`, `prompt` and `settings` are on-device only. */
    fun asTranscriber(): Transcriber = Transcriber { context, uri, _, language, _, speakers, _ ->
        transcribe(context, uri, language, speakers)
    }

    suspend fun transcribe(
        context: Context,
        uri: Uri,
        language: String?,
        speakers: OfflineSpeakerLabeller?,
    ): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        // First, so a Stop left over from an earlier run cannot make this one read as stopped.
        TranscriptionEngine.beginExternalRun()
        // Before any decoding: with no key there is nothing to send, and saying so is the answer.
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw CloudError.notSetUp(endpoint.label)
        val durationMs = runCatching { AudioDecoder.durationMs(context, uri) }.getOrDefault(0L)
        val plan = ChunkPlan.plan(durationMs)
        progressPercent = 0
        AppLogger.i(TAG, "Transcribing ${durationMs / MS_PER_SECOND}s with ${endpoint.engineId} in ${plan.size} pass(es)")
        try {
            val all = mutableListOf<TranscriptSegment>()
            for (index in plan.indices) {
                if (TranscriptionEngine.wasAborted()) break
                val slice = ChunkDecode.decode(context, uri, plan, index, speakers) { TranscriptionEngine.wasAborted() }
                if (slice.audio.isEmpty()) {
                    AppLogger.w(TAG, "Pass ${index + 1}/${plan.size} decoded no audio")
                    continue
                }
                val wav = WavEncoder.encode(slice.audio)
                // Sizes and counts only — never words, never the key.
                AppLogger.i(TAG, "Pass ${index + 1}/${plan.size}: uploading ${wav.size / BYTES_PER_KB} KB")
                val segments = client.transcribe(endpoint, key, wav, language)
                all += ChunkPlan.stitch(segments, plan[index].copy(decodeFromMs = slice.startMs))
                progressPercent = (index + 1) * PERCENT / plan.size
            }
            AppLogger.i(TAG, "Produced ${all.size} segments across ${plan.size} pass(es)")
            all
        } finally {
            progressPercent = 0
        }
    }

    companion object {
        private const val TAG = "CV:CloudTranscribe"
        private const val MS_PER_SECOND = 1000L
        private const val BYTES_PER_KB = 1024
        private const val PERCENT = 100

        /** How far through the current recording, by finished passes; 0 when idle. Read by the worker. */
        @Volatile
        var progressPercent: Int = 0
            private set
    }
}
