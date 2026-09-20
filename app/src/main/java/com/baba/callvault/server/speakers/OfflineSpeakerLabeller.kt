/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

/**
 * Works out who was speaking from a recording that has already been made (issue #38).
 *
 * ## What this is for
 *
 * The live capture answers this for free — [SpeakerTurnDetector] watches the two channels as they
 * arrive, before the mono downmix. **Shizuku recordings get no such chance**: scrcpy captures *and*
 * encodes inside its own process, so CallVault never sees the raw channels and
 * `RecordingSession.speakerTurns()` returns nothing. That is the popup mirror176 reported.
 *
 * But the information is not lost — scrcpy captures `VOICE_CALL` in `CHANNEL_IN_STEREO`, so it is still
 * in the delivered file. This reads it back out.
 *
 * ## Why it rides on the transcription decode
 *
 * Transcription already decodes the whole call, chunk by chunk. Feeding those same samples through here
 * costs one extra pass over memory the decoder has already produced — no second decode, no extra
 * battery, and no work at all for a recording nobody transcribes.
 *
 * ## The two ways it declines
 *
 * - The channels turn out to be **mixed** — a mono file, or an encoder that collapsed them at
 *   24 kbps — so [StereoSeparation] says so and this produces nothing.
 * - The file is silent, which proves nothing either way, and again produces nothing.
 *
 * In both cases the answer is *no labels*, never wrong labels. A missing feature is a missing feature;
 * a wrong label is the app telling the user the other party said something they said themselves.
 *
 * Not thread-safe: one instance per transcription, fed from the thread doing the decoding.
 */
class OfflineSpeakerLabeller {

    private val meter = StereoSeparationMeter()

    /** Each chunk's real start, with the turns its own detector produced relative to that start. */
    private val chunks = mutableListOf<Pair<Long, List<SpeakerTurn>>>()

    /**
     * Offers one decoded chunk, exactly as `AudioDecoder.decodeRange`'s `onInterleaved` hands it over.
     *
     * A fresh detector per chunk, because each chunk's detector counts its windows from zero and
     * [SpeakerTurnMerge] is what moves them to where the chunk really began. Anything that is not
     * two-channel is ignored — there is no second party to find in a mono recording.
     */
    fun accept(pcm: ShortArray, length: Int, channels: Int, sampleRate: Int, startMs: Long) {
        if (channels != STEREO || length <= 0 || sampleRate <= 0) return

        meter.accept(pcm, length, channels)

        val detector = SpeakerTurnDetector(sampleRate)
        detector.accept(pcm, length)
        chunks += startMs to detector.finish()
    }

    /** What the channels turned out to be. Worth logging: it explains an empty [finish]. */
    fun separation(): StereoSeparation = meter.separation()

    /**
     * The stitched timeline, or **empty** when the channels may not be trusted.
     *
     * Empty is a normal answer, not an error: it is what every mono recording produces, and what a
     * collapsed stereo file produces. Callers store nothing rather than storing nothing-useful.
     */
    fun finish(): List<SpeakerTurn> {
        if (!separation().mayLabelSpeakers) return emptyList()
        return SpeakerTurnMerge.stitch(chunks)
    }

    private companion object {
        const val STEREO = 2
    }
}
