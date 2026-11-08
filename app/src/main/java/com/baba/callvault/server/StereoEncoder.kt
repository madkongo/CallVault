/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec

/**
 * Opens a two-channel encoder for stereo output (#74), for the encoders that never had to do it before:
 * the VoIP session and the app-side handoff encoder.
 *
 * It brings what the carrier path learned the hard way (issues #18, #28c): the rate is brought inside what
 * the encoder advertises — an out-of-range rate can come out as a full-length silent file — and a refused
 * configure is retried once at the codec's own recommended rate. Both rates are lifted to the stereo floor,
 * so the retry is not a starved file either. If both are refused this throws, and the caller records mono.
 */
object StereoEncoder {

    fun open(
        mime: String,
        sampleRate: Int,
        codec: ScrcpyAudioCodec,
        monoRate: Int,
        maxInputSize: Int,
        warn: (String) -> Unit,
    ): MediaCodec {
        val channels = StereoBitRate.STEREO
        val first = EncoderLimits.resolveBitRate(mime, StereoBitRate.floorFor(codec, monoRate), sampleRate, channels)
        val rates = listOf(first, StereoBitRate.floorFor(codec, codec.defaultBitRate)).distinct()
        var lastFailure: Throwable? = null
        for (rate in rates) {
            val c = MediaCodec.createEncoderByType(mime)
            val configured = runCatching {
                c.configure(format(mime, sampleRate, channels, rate, maxInputSize), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            if (configured.isSuccess) {
                if (rate != first) warn("Stereo encoder refused $first bps; recording at $rate bps")
                return c
            }
            runCatching { c.release() } // a refused configure leaves it unusable
            lastFailure = configured.exceptionOrNull()
        }
        throw IllegalStateException("No stereo $mime encoder would configure at ${rates.joinToString(" or ")} bps", lastFailure)
    }

    /** The audio format every CallVault encoder uses: AAC-LC for AAC, the given rate and input size. */
    fun format(mime: String, sampleRate: Int, channels: Int, bitRate: Int, maxInputSize: Int): MediaFormat =
        MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        }
}
