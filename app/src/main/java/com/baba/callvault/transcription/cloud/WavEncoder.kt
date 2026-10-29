/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16 kHz mono float → a PCM-16 WAV file in memory, the upload format for the cloud path (issue #48).
 *
 * WAV because every OpenAI-compatible service accepts it and it needs no encoder: the samples the
 * on-device path already decodes go out as they are. 📐 32 KB per second, so a five-minute chunk is
 * about 9.6 MB and the six-minute cap 11.5 MB, under the 25 MB upload limit Groq and OpenRouter state.
 */
object WavEncoder {

    const val SAMPLE_RATE = 16_000
    const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val PCM_FORMAT: Short = 1
    private const val FMT_CHUNK_BYTES = 16

    /** [mono] in [-1, 1]; anything outside is clipped rather than wrapped. */
    fun encode(mono: FloatArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = mono.size * BYTES_PER_SAMPLE
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(HEADER_BYTES - 8 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(FMT_CHUNK_BYTES)
        buffer.putShort(PCM_FORMAT)
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * CHANNELS * BYTES_PER_SAMPLE)
        buffer.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (sample in mono) {
            buffer.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }
        return buffer.array()
    }

    /** One second of silence: the smallest real request, used by the Settings "Test" button. */
    fun silence(seconds: Int = 1): ByteArray = encode(FloatArray(SAMPLE_RATE * seconds))
}
