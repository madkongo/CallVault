/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import android.content.Context
import android.media.MediaMuxer
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Transcribes a recording with a cloud service (issue #48).
 *
 * The recording goes up as it is — its own Ogg/Opus or M4A bytes, in ONE request when it is under
 * [UploadFormat.SINGLE_UPLOAD_MAX_BYTES], which is every call under the length limit at the default
 * bit rate. Only larger files, or containers the services do not take, are cut into passes (remuxed,
 * not re-encoded, where possible; WAV otherwise) and joined by [CloudSeam]. Decoding for upload used to
 * cost ~20 s per five-minute pass on the OP9, ten times the network round trip.
 *
 * A stereo recording is still decoded on the phone, alongside the upload, because the speaker labeller
 * reads who spoke from its raw channels; a mono one is not decoded at all.
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
        // Before anything else: with no key there is nothing to send, and saying so is the answer.
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw CloudError.notSetUp(endpoint.label)
        val durationMs = runCatching { AudioDecoder.durationMs(context, uri) }.getOrDefault(0L)
        val probe = CloudAudioSource.probe(context, uri)
        progressPercent = 0
        try {
            coroutineScope {
                // Who spoke is read from the raw channels of a local decode. A mono file has none to
                // read, so it is not decoded at all; a stereo one is decoded alongside the upload, so
                // its cost hides behind the network instead of adding to it.
                val labelling = if (speakers != null && probe.channels >= 2) {
                    async { decodeForSpeakers(context, uri, durationMs, speakers) }
                } else null
                val segments = upload(context, uri, key, language, durationMs, probe)
                labelling?.await()
                AppLogger.i(TAG, "Produced ${segments.size} segments")
                segments
            }
        } finally {
            progressPercent = 0
        }
    }

    /** The whole recording in one request where it fits, else in passes. */
    private fun upload(
        context: Context,
        uri: Uri,
        key: String,
        language: String?,
        durationMs: Long,
        probe: CloudAudioSource.Probe,
    ): List<TranscriptSegment> {
        if (UploadFormat.sendsWhole(probe.fileName, probe.sizeBytes)) {
            val format = requireNotNull(UploadFormat.forFileName(probe.fileName))
            // Sizes only — never words, never the key.
            AppLogger.i(TAG, "Sending the recording whole: ${probe.sizeBytes / BYTES_PER_KB} KB as ${format.contentType}")
            try {
                return client.transcribe(endpoint, key, CloudAudioSource.readAll(context, uri), format, language)
                    .also { progressPercent = PERCENT }
            } catch (e: CloudError) {
                // A service that does not take this container (400/415) or this size (413) gets WAV passes.
                if (e.kind != CloudError.Kind.REJECTED && e.kind != CloudError.Kind.TOO_LARGE) throw e
                AppLogger.w(TAG, "${endpoint.label} refused the recording as it is (${e.httpStatus}); sending WAV passes")
                return inPasses(context, uri, key, language, durationMs, remuxAs = null)
            }
        }
        return inPasses(context, uri, key, language, durationMs, remuxAs = UploadFormat.forRemuxedCodec(probe.trackMime))
    }

    /**
     * Passes of five minutes with a [CloudSeam.CLOUD_OVERLAP_MS] run-up, joined by [CloudSeam]. Each is
     * remuxed into [remuxAs] when given — no decode — and decoded to WAV otherwise or when refused.
     */
    private fun inPasses(
        context: Context,
        uri: Uri,
        key: String,
        language: String?,
        durationMs: Long,
        remuxAs: UploadFormat.Named?,
    ): List<TranscriptSegment> {
        val plan = ChunkPlan.plan(durationMs, overlapMs = CloudSeam.CLOUD_OVERLAP_MS)
        AppLogger.i(TAG, "Sending ${durationMs / MS_PER_SECOND}s in ${plan.size} pass(es), ${remuxAs?.contentType ?: "audio/wav"}")
        val passes = mutableListOf<CloudSeam.Pass>()
        var remux = remuxAs
        for (index in plan.indices) {
            if (TranscriptionEngine.wasAborted()) break
            val chunk = plan[index]
            val sent = remux?.let { format ->
                runCatching { sendRemuxed(context, uri, key, language, plan, index, format) }
                    .onFailure {
                        if (it is CloudError && it.kind != CloudError.Kind.REJECTED) throw it
                        AppLogger.w(TAG, "Pass ${index + 1}: remuxed upload failed (${it.javaClass.simpleName}); using WAV")
                        remux = null
                    }.getOrNull()
            } ?: sendDecoded(context, uri, key, language, plan, index)
            passes += CloudSeam.Pass(chunk.copy(decodeFromMs = sent.first), sent.second)
            progressPercent = (index + 1) * PERCENT / plan.size
        }
        return CloudSeam.join(passes)
    }

    /** One pass, packets copied: (where it really starts, its segments on the call's timeline). */
    private fun sendRemuxed(
        context: Context, uri: Uri, key: String, language: String?,
        plan: List<ChunkPlan.Chunk>, index: Int, format: UploadFormat.Named,
    ): Pair<Long, List<TranscriptSegment>> {
        val chunk = plan[index]
        val outputFormat = if (format.contentType == "audio/ogg") MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val toMs = if (plan.size == 1 || chunk.endMs <= 0L) Long.MAX_VALUE else chunk.endMs
        val cut = CloudAudioSource.remux(context, uri, chunk.decodeFromMs, toMs, outputFormat, context.cacheDir)
            ?: error("No audio track")
        if (cut.bytes.isEmpty()) return cut.startMs to emptyList()
        AppLogger.i(TAG, "Pass ${index + 1}/${plan.size}: uploading ${cut.bytes.size / BYTES_PER_KB} KB from ${cut.startMs} ms")
        return cut.startMs to shift(client.transcribe(endpoint, key, cut.bytes, format, language), cut.startMs)
    }

    /** One pass decoded to WAV — the fallback for containers that cannot be remuxed or were refused. */
    private fun sendDecoded(
        context: Context, uri: Uri, key: String, language: String?,
        plan: List<ChunkPlan.Chunk>, index: Int,
    ): Pair<Long, List<TranscriptSegment>> {
        val slice = ChunkDecode.decode(context, uri, plan, index, speakers = null) { TranscriptionEngine.wasAborted() }
        if (slice.audio.isEmpty()) return slice.startMs to emptyList()
        val wav = WavEncoder.encode(slice.audio)
        AppLogger.i(TAG, "Pass ${index + 1}/${plan.size}: uploading ${wav.size / BYTES_PER_KB} KB of WAV")
        return slice.startMs to shift(client.transcribe(endpoint, key, wav, language), slice.startMs)
    }

    /** Decodes the recording only to hand its channels to the speaker labeller; the audio is dropped. */
    private fun decodeForSpeakers(context: Context, uri: Uri, durationMs: Long, speakers: OfflineSpeakerLabeller) {
        val plan = ChunkPlan.plan(durationMs)
        for (index in plan.indices) {
            if (TranscriptionEngine.wasAborted()) return
            ChunkDecode.decode(context, uri, plan, index, speakers) { TranscriptionEngine.wasAborted() }
        }
    }

    private fun shift(segments: List<TranscriptSegment>, byMs: Long) =
        segments.map { it.copy(startMs = it.startMs + byMs, endMs = it.endMs + byMs) }

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
