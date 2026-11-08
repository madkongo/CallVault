/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

/**
 * Shape of a delivered capture control block: where the audio ring sits inside the cblk ashmem region
 * and how the drain must walk it.
 *
 * Every field arrives over IPC and is fed straight into native pointer arithmetic, so this type exists
 * to keep that arithmetic — and the validation that must precede it — in one pure, testable place.
 * Nothing may mmap or drain a cblk before [validationError] has returned null for it.
 *
 * @param frameCount frames the track was allocated (`AudioRecord.bufferSizeInFrames`).
 * @param sampleRate capture rate in Hz.
 * @param channels   captured channels (1 mono, 2 interleaved stereo).
 * @param cblkSize   true byte size of the ashmem region, from `ASHMEM_GET_SIZE`.
 */
data class HandoffGeometry(
    val frameCount: Int,
    val sampleRate: Int,
    val channels: Int,
    val cblkSize: Int,
) {
    /** Bytes per frame across all captured channels (PCM-16). */
    val frameSize: Int get() = channels * BYTES_PER_SAMPLE

    /**
     * The frame count the ring PHYSICALLY wraps at: positions are masked with `wrapFrames - 1`, so the
     * ring rounds up to the next power of two rather than wrapping at [frameCount].
     *
     * This mirrors `AudioRecordClientProxy::obtainBuffer` (`front &= mFrameCountP2 - 1`). Wrapping at
     * [frameCount] instead reads the untouched region between [frameCount] and the rounded-up size,
     * which is exactly the periodic-gap bug this constant was introduced to fix — every 48 kHz
     * configuration has a non-power-of-two frame count.
     */
    /**
     * **Pass THIS to the native drain, never [frameCount].** The native side rounds whatever it is
     * given up to the next power of two and masks positions with it, so handing it the raw reported
     * count would reproduce the oversized ring this property exists to correct — and the read would go
     * past the end of the mapping, which is precisely what the size check was catching.
     * `roundup(wrapFrames) == wrapFrames`, so passing it through is safe.
     */
    val wrapFrames: Int
        get() {
            // What the reported frame count implies…
            var reported = 1
            while (reported < frameCount) reported = reported shl 1

            // …and what the mapping can actually hold. AudioFlinger allocates
            // `roundup(frameCount) * frameSize` immediately after the control block, so the region's
            // own size is ground truth; the reported count is not. `getBufferSizeInFrames()` returns
            // `cblk->mBufferSizeInFrames`, a LOGICAL value the client overwrites on every attach — it
            // is not the field that sizes the ring, and the two only agree by coincidence in stock
            // AOSP. On a Galaxy S24 FE they diverged: 8192 reported against a 4096-frame allocation,
            // which would have read 12 KB past the end had the size check not refused it.
            val fits = (cblkSize - dataOff) / frameSize
            var fitting = 1
            while (fitting * 2 <= fits) fitting = fitting shl 1

            return if (fitting < reported) fitting else reported
        }

    /**
     * Byte offset of the PCM ring within the shared region — i.e. `sizeof(audio_track_cblk_t)`.
     *
     * Version-dependent, and getting it wrong reads the ring misaligned rather than failing loudly:
     * 224 bytes on Android 11, 228 on 12, 232 on 13 through 16 (derived from AOSP field-by-field and
     * cross-checked against this device's observed `mFront`/`mRear` at 184/188). `minSdk` is 30, so
     * hardcoding 232 was wrong on Android 11 and 12.
     */
    val dataOff: Int get() = when {
        android.os.Build.VERSION.SDK_INT >= 33 -> 232
        android.os.Build.VERSION.SDK_INT >= 31 -> 228
        else -> 224
    }

    /** Last byte of the ring, exclusive — must fit inside [cblkSize]. */
    val ringEndOffset: Long get() = dataOff + wrapFrames.toLong() * frameSize

    /**
     * Why this geometry must not be used, or null if it is safe to drain.
     *
     * The delivery arrives over an exported provider. Callers already reject untrusted UIDs, but the
     * values still get turned into native offsets, so a corrupt or hostile payload must be refused here
     * rather than becoming an out-of-bounds read.
     */
    fun validationError(): String? = when {
        channels !in MIN_CHANNELS..MAX_CHANNELS -> "bad channels=$channels"
        sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE -> "bad rate=$sampleRate"
        frameCount <= 0 -> "bad frameCount=$frameCount"
        cblkSize <= 0 -> "fd is not a sized ashmem region (size=$cblkSize)"
        wrapFrames < MIN_RING_FRAMES -> "ring too small to be real (wrapFrames=$wrapFrames)"
        ringEndOffset > cblkSize ->
            "geometry doesn't fit ashmem (dataOff=$dataOff wrapFrames=$wrapFrames frameSize=$frameSize > $cblkSize)"
        else -> null
    }

    companion object {
        /**
         * Byte offset of the audio buffer for Android 13+ — kept for callers that need a constant.
         * Prefer the instance [dataOff], which is correct on Android 11 and 12 as well.
         */
        const val DATA_OFF = 232

        /**
         * Below this, the derived ring is too small to be a real capture buffer — which is what a
         * `FAST` record track looks like, since those keep their PCM in a separate pipe and leave
         * nothing usable after the control block.
         */
        private const val MIN_RING_FRAMES = 256

        /**
         * Freshest frames held back each drain cycle — they may still be mid-write by the server.
         *
         * **This must exceed one HAL write burst.** AudioFlinger's record thread does not advance
         * `mRear` sample by sample; it publishes a whole HAL period at a time, and while that copy is
         * in flight the frames just below `mRear` are partially written. A guard smaller than one
         * burst therefore lets the drain read half-written frames — heard as crackle, aperiodic
         * because it depends on where the 5 ms read cycle happens to land inside the write burst.
         *
         * It was 32 frames — **0.67 ms at 48 kHz** — against HAL periods that are typically 4-20 ms
         * (192-960 frames), so it was 6x to 30x too small. It survived because the artefact is
         * timing-sensitive: a OnePlus 12 sounded clean while a Galaxy S24 FE crackled on every call
         * (measured 2026-07-30, A/B against Resilient recording off).
         *
         * 960 frames = 20 ms covers the largest common period. The cost is 20 ms of extra latency
         * before a frame reaches the encoder, which is irrelevant for call recording, and 20 ms more
         * of the ring left unread — trivial against a ring measured in thousands of frames.
         */
        const val GUARD_FRAMES = 960

        private const val BYTES_PER_SAMPLE = 2 // PCM-16
        private const val MIN_CHANNELS = 1
        private const val MAX_CHANNELS = 2
        private const val MIN_SAMPLE_RATE = 8_000
        private const val MAX_SAMPLE_RATE = 192_000

        /** Frame count assumed when the daemon could not report one. */
        const val FRAME_COUNT_FALLBACK = 2048

        /** Builds a geometry, substituting [FRAME_COUNT_FALLBACK] for a missing frame count. */
        fun of(frameCount: Int, sampleRate: Int, channels: Int, cblkSize: Int) = HandoffGeometry(
            frameCount = if (frameCount > 0) frameCount else FRAME_COUNT_FALLBACK,
            sampleRate = sampleRate,
            channels = channels,
            cblkSize = cblkSize,
        )
    }
}
