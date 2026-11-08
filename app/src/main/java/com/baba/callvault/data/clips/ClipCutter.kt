/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.clips

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.baba.callvault.data.merge.MergeFormat
import com.baba.callvault.data.merge.MergeFormat.optInt
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts a part out of a recording into a new file, without re-encoding.
 *
 * The same frame copy as un-merge ([com.baba.callvault.data.merge.AudioSplit]): encoded frames go from the recording
 * to the clip unchanged, so the clip keeps the call's quality and both channels of a stereo recording. Opus goes into
 * Ogg, AAC into MP4 — the two formats CallVault records in. Anything else (an imported MP3, WAV, FLAC) cannot be
 * written by Android's muxer and is refused; [canClip] says so up front.
 *
 * A decoder starting in the middle of a stream needs a little audio before the cut to settle, or the clip opens with
 * a click (RFC 7845 §4.6 for Opus). So the copy starts [leadInMsFor] early and the clip carries that much extra at its
 * start — 80 ms for Opus, two frames for AAC.
 */
object ClipCutter {

    private const val TAG = "CV:ClipCutter"
    private const val OPUS_LEAD_IN_MS = 80L
    private const val AAC_LEAD_IN_MS = 43L

    /** A written clip and how much audio before the chosen start it holds. */
    data class Result(val file: File, val leadInMs: Long, val mime: String)

    /** Clips can be made from Opus and AAC recordings only. */
    fun canClip(mime: String?): Boolean =
        mime == MediaFormat.MIMETYPE_AUDIO_OPUS || mime == MediaFormat.MIMETYPE_AUDIO_AAC

    /** The recording's audio type, or null when it cannot be read. */
    fun mimeOf(context: Context, uri: Uri): String? = runCatching {
        withSource(context, uri) { fd ->
            val (extractor, format) = MergeFormat.openAudio(fd)
            extractor.release()
            format.getString(MediaFormat.KEY_MIME)
        }
    }.onFailure { AppLogger.w(TAG, "Could not read the recording's format: ${it.message}") }.getOrNull()

    /**
     * Runs [block] on a seekable descriptor of [uri]. A Drive-only recording comes through a provider that may hand back
     * a pipe, which MediaExtractor cannot seek; then the recording is copied to the cache first and read from there.
     */
    private fun <T> withSource(context: Context, uri: Uri, block: (java.io.FileDescriptor) -> T): T {
        val direct = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // A pipe has no size; a real file does.
                if (pfd.statSize <= 0L) null else block(pfd.fileDescriptor)
            }
        }.getOrNull()
        if (direct != null) return direct
        val copy = File(ClipFiles.directory(context), ".source.tmp").apply { parentFile?.mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: error("Cannot open the recording")
            return java.io.FileInputStream(copy).use { block(it.fd) }
        } finally {
            copy.delete()
        }
    }

    fun extensionFor(mime: String): String = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) "ogg" else "m4a"

    fun shareMimeFor(mime: String): String = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) "audio/ogg" else "audio/mp4"

    private fun leadInMsFor(mime: String): Long =
        if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) OPUS_LEAD_IN_MS else AAC_LEAD_IN_MS

    /**
     * Writes [range] of [source] to [output]. Blocking; call it off the main thread. Throws when the recording cannot
     * be read or is not a format a clip can be made from.
     */
    fun cut(context: Context, source: Uri, range: ClipRange, output: File): Result = try {
        withSource(context, source) { fd ->
            val (extractor, format) = MergeFormat.openAudio(fd)
            try {
                val mime = format.getString(MediaFormat.KEY_MIME) ?: error("No audio type")
                require(canClip(mime)) { "Clips cannot be made from $mime" }
                val leadIn = leadInMsFor(mime).coerceAtMost(range.startMs)
                val firstUs = copy(extractor, format, mime, (range.startMs - leadIn) * 1_000, range.endMs * 1_000, output)
                if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS && !OggEndOfStream.mark(output)) {
                    AppLogger.w(TAG, "Could not mark the clip's last Ogg page as the end of the stream")
                }
                val actualLeadIn = (range.startMs - firstUs / 1_000).coerceAtLeast(0L)
                AppLogger.i(TAG, "Clip ${range.startMs}-${range.endMs} ms written (${output.length()} bytes, lead-in $actualLeadIn ms)")
                Result(output, actualLeadIn, mime)
            } finally {
                extractor.release()
            }
        }
    } catch (e: Exception) {
        // Never leave a half-written clip where a later share could pick it up.
        output.delete()
        throw e
    }

    /** Copies the frames from [fromUs] up to [toUs]; returns the time of the first frame copied. */
    private fun copy(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String,
        fromUs: Long,
        toUs: Long,
        output: File,
    ): Long {
        val outFormat = MediaFormat.createAudioFormat(
            mime,
            format.optInt(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1),
            format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1),
        ).apply {
            // Without the codec-specific data the clip is a file no decoder will open.
            listOf("csd-0", "csd-1", "csd-2").forEach { key ->
                if (format.containsKey(key)) setByteBuffer(key, format.getByteBuffer(key))
            }
            listOf(MediaFormat.KEY_BIT_RATE, MediaFormat.KEY_MAX_INPUT_SIZE, MediaFormat.KEY_AAC_PROFILE).forEach { key ->
                if (format.containsKey(key)) setInteger(key, format.getInteger(key))
            }
        }
        val muxerFormat = MergeFormat.muxerFormatFor(format)
        output.parentFile?.mkdirs()
        val muxer = MediaMuxer(output.absolutePath, muxerFormat)
        val track = muxer.addTrack(outFormat)
        muxer.start()

        val capacity = format.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, MergeFormat.FALLBACK_MAX_INPUT_SIZE)
            .coerceAtLeast(MergeFormat.FALLBACK_MAX_INPUT_SIZE)
        val buffer = ByteBuffer.allocate(capacity)
        val info = MediaCodec.BufferInfo()
        var baseUs = -1L
        var written = 0
        try {
            seekAtOrBefore(extractor, fromUs)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val at = extractor.sampleTime
                if (at >= toUs) break
                if (at >= fromUs - FRAME_SLACK_US) {
                    if (baseUs < 0) baseUs = at
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = at - baseUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(track, buffer, info)
                    written++
                }
                if (!extractor.advance()) break
            }
            check(written > 0) { "Nothing was copied" }
            // Not swallowed: a failed stop means an unfinished file, which must not be shared (review 2026-11-04).
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        return baseUs.coerceAtLeast(0L)
    }

    /**
     * Puts [extractor] at or before [targetUs]. Every Opus and AAC frame is a sync frame, but Android's Ogg seek is by
     * page bisection and can land seconds AFTER the target in a long file — measured on the OP9 2026-11-04: a 30 s
     * part of a 57-minute call came out 24.5 s, its first 5.5 s missing. So step back until the first frame is not
     * later than the target; the copy then skips forward frame by frame to the exact start.
     */
    private fun seekAtOrBefore(extractor: MediaExtractor, targetUs: Long) {
        var backUs = 0L
        while (true) {
            val at = (targetUs - backUs).coerceAtLeast(0L)
            extractor.seekTo(at, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val landed = extractor.sampleTime
            if (landed in 0..targetUs || at == 0L) {
                if (backUs > 0) AppLogger.i(TAG, "Seek landed late; stepped back ${backUs / 1000} ms to reach the start")
                return
            }
            backUs = if (backUs == 0L) SEEK_STEP_US else backUs * 2
        }
    }

    private const val SEEK_STEP_US = 5_000_000L

    /** A frame starting up to this much before the requested start still counts as "at" it (one 20–23 ms frame). */
    private const val FRAME_SLACK_US = 25_000L
}

/**
 * Where clips live: a cache folder of their own, shared through the FileProvider and never part of the library or
 * Drive (maintainer, 2026-11-04: a clip is never saved as a recording).
 */
object ClipFiles {

    private const val DIRECTORY = "clips"

    /** Kept this long so an app that reads the shared file late (an email draft) still finds it. */
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /**
     * A new file named [name] for a clip, in a folder of its own: sharing the same part twice must not replace a file an
     * app is still reading from the first share (review 2026-11-04). Clears clips older than a day first.
     */
    fun newFile(context: Context, name: String, now: Long = System.currentTimeMillis()): File {
        clearOld(context, now)
        val dir = File(directory(context), now.toString()).apply { mkdirs() }
        return File(dir, name)
    }

    fun clearOld(context: Context, now: Long = System.currentTimeMillis()) {
        directory(context).listFiles()?.forEach { if (now - it.lastModified() > KEEP_MS) it.deleteRecursively() }
    }
}
