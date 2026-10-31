/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.net.Uri
import com.baba.callvault.server.speakers.OfflineSpeakerLabeller
import com.baba.callvault.utils.AppLogger

/**
 * Decodes one pass of a [ChunkPlan] to 16 kHz mono, offering the raw channels to the speaker labeller
 * on the way.
 *
 * Shared by the on-device engine and the cloud path (issue #48), so the two cannot drift apart in the
 * one place where a drift moves every timestamp: which range is asked for, and where the audio really
 * started.
 */
internal object ChunkDecode {

    private const val TAG = "CV:ChunkDecode"

    fun decode(
        context: Context,
        uri: Uri,
        plan: List<ChunkPlan.Chunk>,
        index: Int,
        speakers: OfflineSpeakerLabeller?,
        shouldStop: () -> Boolean,
    ): AudioDecoder.DecodedRange {
        val chunk = plan[index]
        // A single-pass plan asks for the WHOLE file explicitly, rather than for a range that happens
        // to cover it. The difference is not cosmetic: a range decode cannot check the decoded length
        // against the container's declared duration — a chunk is *meant* to be shorter — so naming a
        // range here would quietly disable the guard that exists because a 45-second clip once
        // transcribed for over eleven minutes.
        val singlePass = plan.size == 1
        val slice = AudioDecoder.decodeRange(
            context, uri,
            fromMs = if (singlePass) 0L else chunk.decodeFromMs,
            toMs = if (singlePass || chunk.endMs <= 0L) Long.MAX_VALUE else chunk.endMs,
            // Wrapped: speaker labels are a bonus on top of a transcript, and nothing about them is
            // worth losing the transcript for.
            onInterleaved = speakers?.let { labeller ->
                { pcm, length, channels, rate, startMs ->
                    runCatching { labeller.accept(pcm, length, channels, rate, startMs) }
                        .onFailure { AppLogger.w(TAG, "speaker labelling skipped this chunk: ${it.message}") }
                    Unit
                }
            },
            shouldStop = shouldStop,
        )

        // The number that would have caught the first chunking failure without a benchmark: where the
        // slice was asked to begin, and where it says it really began. A chunk whose audio does not
        // start where its timestamps claim shifts every line inside it.
        if (!singlePass) {
            AppLogger.i(
                TAG,
                "Pass ${index + 1}/${plan.size}: asked ${chunk.decodeFromMs} ms, decoded from " +
                    "${slice.startMs} ms (drift ${slice.startMs - chunk.decodeFromMs} ms), " +
                    "${slice.audio.size / AudioDecoder.TARGET_SAMPLE_RATE}s of audio",
            )
        }
        return slice
    }
}
