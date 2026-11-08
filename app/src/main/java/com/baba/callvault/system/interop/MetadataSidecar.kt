/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.interop

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.merge.MergeFormat
import com.baba.callvault.data.merge.MergeFormat.optInt
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.StereoBitRate
import com.baba.callvault.utils.AppLogger

/**
 * Writes the BCR-compatible `.json` details file beside a finished recording, when the user has
 * asked for it.
 *
 * Opt-in and off by default: it puts a second file in the user's folder for every call, which is a
 * visible change to something they look at, and it only earns its keep for people using a tool that
 * reads it.
 */
object MetadataSidecar {

    private const val TAG = "CV:MetadataSidecar"
    private const val MIME_JSON = "application/json"

    /** BCR's own naming: the audio file's name with the extension replaced by `.json`. */
    internal fun sidecarNameFor(audioName: String): String =
        audioName.substringBeforeLast('.', audioName) + ".json"

    /**
     * The three format strings BCR's `output.format` wants, for one of our codecs.
     *
     * Written out per codec rather than assembled from the enum's fields: `type` is BCR's own
     * container/codec label ("OGG/Opus"), not something derivable from a MIME type, and guessing at
     * its shape is exactly the kind of near-miss that makes a reader show blanks.
     */
    private fun formatFor(codec: ScrcpyAudioCodec): Triple<String, String, String> = when (codec) {
        ScrcpyAudioCodec.OPUS -> Triple("OGG/Opus", "audio/ogg", "audio/opus")
        ScrcpyAudioCodec.AAC -> Triple("M4A/AAC", "audio/mp4", "audio/mp4a-latm")
    }

    /**
     * Writes the sidecar for [audioName] into [folderUri]. No-op when the setting is off.
     *
     * Never throws. A details file is a convenience for other tools; failing to write one must not
     * turn a successful recording into a failed one.
     *
     * @param packageName "com.android.phone" for a carrier call, the messenger's package for a VoIP one.
     * @param direction   "in" / "out" / "conference", or null when we genuinely do not know — an app
     *                    call has no reliable direction, and BCR's format has a null for that.
     * @param nearChannel which captured channel carries the user ("A"/"B"), or null when not known —
     *                    "A" for an app call (near is on the left by construction), the learned
     *                    carrier mapping only when it is trusted. Goes in the `callvault` block.
     *
     * `suspend`, and meant to be called **after** the call's speaker turns have been collected: it
     * reads those stored turns back for the recording and folds them into the file (issue #50).
     */
    suspend fun writeIfEnabled(
        context: Context,
        folderUri: Uri?,
        audioName: String,
        timestampUnixMs: Long,
        packageName: String?,
        direction: String?,
        phoneNumber: String?,
        contactName: String?,
        durationSecsEncoded: Double?,
        nearChannel: String?,
        simSlot: Int? = null,
    ) {
        val prefs = AppPreferences(context)
        if (!prefs.isWriteMetadataFileEnabled()) return
        if (folderUri == null) {
            AppLogger.w(TAG, "No recording folder; not writing a details file for '$audioName'.")
            return
        }

        // The turns the daemon or handoff measured during the call, stored by collectAfterCall just
        // before this runs. Blank for a mono capture, a Shizuku recording, or an older daemon — in
        // which case the block simply omits the key rather than claiming empty turns.
        val speakerTurns = SpeakerTurnsRepository.turnsFor(context, audioName).ifBlank { null }

        runCatching {
            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: error("folder $folderUri could not be opened")
            val codec = ScrcpyAudioCodec.entries
                .firstOrNull { it.cliKey == prefs.getAudioCodec() } ?: ScrcpyAudioCodec.OPUS
            val (type, container, audio) = formatFor(codec)
            // Read from the finished file, not assumed. This used to say 1 for every recording — wrong
            // for the scrcpy fallback all along, and wrong for stereo output (#74), whose users are the
            // very people reading these files with their own tools. Unknown is written as null.
            val shape = probeShape(context, folder, audioName)

            val json = BcrMetadata.build(
                BcrMetadata.Input(
                    timestampUnixMs = timestampUnixMs,
                    packageName = packageName,
                    direction = direction,
                    phoneNumber = phoneNumber,
                    contactName = contactName,
                    formatType = type,
                    mimeTypeContainer = container,
                    mimeTypeAudio = audio,
                    parameterType = "bitrate",
                    // The rate asked of the encoder for the channels actually recorded (stereo is floored).
                    parameter = StereoBitRate.requestedRate(codec, prefs.getAudioBitRate(), shape.channels ?: StereoBitRate.MONO),
                    sampleRate = shape.sampleRate,
                    channelCount = shape.channels,
                    durationSecsEncoded = durationSecsEncoded,
                    speakerTurns = speakerTurns,
                    nearChannel = nearChannel,
                    simSlot = simSlot,
                )
            )

            val name = sidecarNameFor(audioName)
            // Replace rather than accumulate "name (1).json": a re-run for the same recording should
            // correct the details file, not litter the user's folder with rivals to it.
            folder.findFile(name)?.delete()
            val doc = folder.createFile(MIME_JSON, name) ?: error("could not create $name")
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(json.toByteArray()) }
                ?: error("could not open $name for writing")
            AppLogger.i(TAG, "Wrote details file '$name'.")
        }.onFailure {
            AppLogger.w(TAG, "Could not write the details file for '$audioName': ${it.message}")
        }
    }

    /** Channel count and sample rate as recorded; nulls when the file cannot be read. */
    private data class AudioShape(val channels: Int?, val sampleRate: Int?)

    private fun probeShape(context: Context, folder: DocumentFile, audioName: String): AudioShape =
        runCatching {
            val uri = folder.findFile(audioName)?.uri ?: return@runCatching AudioShape(null, null)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = MergeFormat.audioTrackOf(extractor)
                if (track < 0) return@runCatching AudioShape(null, null)
                val f = extractor.getTrackFormat(track)
                AudioShape(
                    f.optInt(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 },
                    f.optInt(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 },
                )
            } finally {
                extractor.release()
            }
        }.onFailure { AppLogger.w(TAG, "Could not read the recording's format for its details file: ${it.message}") }
            .getOrDefault(AudioShape(null, null))
}
