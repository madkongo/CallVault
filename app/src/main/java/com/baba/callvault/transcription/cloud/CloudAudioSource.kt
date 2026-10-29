/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer

/**
 * Reads a recording for the cloud path without decoding it: what it is, its bytes, and a pass of it
 * remuxed — packets copied into a new container, never re-encoded (issue #48).
 */
object CloudAudioSource {

    /** What the cloud path needs to know before choosing how to send a recording. */
    data class Probe(
        val fileName: String?,
        val sizeBytes: Long,
        val trackMime: String?,
        /** 0 when unknown. Mono files give the speaker labeller nothing, so they are not decoded at all. */
        val channels: Int,
    )

    fun probe(context: Context, uri: Uri): Probe {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
        }
        if (name == null) name = uri.lastPathSegment
        if (size < 0) {
            size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }
                .getOrNull() ?: 0L
        }
        val (mime, channels) = runCatching { trackInfo(context, uri) }.getOrDefault(null to 0)
        return Probe(name, size.coerceAtLeast(0L), mime, channels)
    }

    private fun trackInfo(context: Context, uri: Uri): Pair<String?, Int> {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
            val format = audioTrack(extractor)?.let { extractor.getTrackFormat(it) } ?: return null to 0
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            return format.getString(MediaFormat.KEY_MIME) to channels
        } finally {
            extractor.release()
        }
    }

    /** The whole file. Only called under [UploadFormat.SINGLE_UPLOAD_MAX_BYTES]. */
    fun readAll(context: Context, uri: Uri): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open the recording" }.use { it.readBytes() }

    /** A remuxed pass: its bytes, and where its first packet really sits in the call. */
    data class Cut(val bytes: ByteArray, val startMs: Long) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * The packets from [fromMs] (at the sync point at or before it) to [toMs], in [outputFormat]
     * (a `MediaMuxer.OutputFormat`). Returns null when the file has no audio track.
     */
    fun remux(context: Context, uri: Uri, fromMs: Long, toMs: Long, outputFormat: Int, cacheDir: File): Cut? {
        val extractor = MediaExtractor()
        val out = File.createTempFile("cloud-pass", ".bin", cacheDir)
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
            val track = audioTrack(extractor) ?: return null
            extractor.selectTrack(track)
            extractor.seekTo(fromMs * US_PER_MS, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val muxer = MediaMuxer(out.absolutePath, outputFormat)
            var firstUs = -1L
            try {
                val target = muxer.addTrack(extractor.getTrackFormat(track))
                muxer.start()
                val buffer = ByteBuffer.allocate(BUFFER_BYTES)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    val timeUs = extractor.sampleTime
                    if (size < 0 || timeUs < 0 || (toMs != Long.MAX_VALUE && timeUs > toMs * US_PER_MS)) break
                    if (firstUs < 0) firstUs = timeUs
                    // Times start at zero in the new file, so the service's timestamps are pass-relative.
                    info.set(0, size, timeUs - firstUs, extractor.sampleFlags)
                    muxer.writeSampleData(target, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
            if (firstUs < 0) return Cut(ByteArray(0), fromMs)
            return Cut(out.readBytes(), firstUs / US_PER_MS)
        } finally {
            extractor.release()
            out.delete()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount).firstOrNull { i ->
        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    private const val US_PER_MS = 1000L

    /** Comfortably above one Opus or AAC packet. */
    private const val BUFFER_BYTES = 256 * 1024
}
