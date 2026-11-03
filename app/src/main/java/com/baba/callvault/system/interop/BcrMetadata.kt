/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.interop

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the sidecar JSON that BCR (`chenxiaolong/BCR`) writes beside each recording.
 *
 * We already replicate BCR's *filename* format, which is what lets `bcr-gui` and friends list our
 * recordings at all. This is the other half: the details file those tools read for the number, the
 * contact and the call direction. Writing it costs one small file and makes every tool built around
 * BCR's output work with CallVault's, with no coordination from anyone.
 *
 * **The schema is copied from BCR's README, not invented.** That matters more here than anywhere
 * else in the app: a plausible-looking file with the wrong key names is worse than no file, because
 * the reader silently shows blanks instead of failing.
 *
 * BCR guarantees only `timestamp_unix_ms`, `timestamp` and `output.format.*`; every other field is
 * set to **null** when it cannot be determined. That is what makes this safe for us to emit — where
 * CallVault does not know something (the SIM slot, the encoder's exact frame counts) the honest
 * answer is already part of the format, and we never have to guess a value to fill a hole.
 */
object BcrMetadata {

    /**
     * What CallVault knows about one finished recording. Everything optional is nullable, and a null
     * reaches the file as a JSON null rather than a missing key — see [putOrNull].
     *
     * @param packageName        The app that handled the call: "com.android.phone" for a carrier
     *                           call, the messenger's package for an app call.
     * @param direction          "in", "out" or "conference", per BCR. Null when unknown.
     * @param parameterType      "bitrate", "compression_level" or "none".
     * @param speakerTurns       The `SpeakerTurnCodec` string CallVault measured, or null when there
     *                           are none. Goes in the namespaced `callvault` block, never a BCR field.
     * @param nearChannel        Which channel carries the user ("A"/"B"), or null when not known.
     */
    data class Input(
        val timestampUnixMs: Long,
        val packageName: String?,
        val direction: String?,
        val phoneNumber: String?,
        val contactName: String?,
        val formatType: String,
        val mimeTypeContainer: String,
        val mimeTypeAudio: String,
        val parameterType: String,
        val parameter: Int?,
        val sampleRate: Int?,
        val channelCount: Int?,
        val durationSecsEncoded: Double?,
        val speakerTurns: String? = null,
        val nearChannel: String? = null,
        /** 1-based SIM slot the call used, when the opt-in SIM label is on and the call log says; else null. */
        val simSlot: Int? = null,
    )

    /** The `callvault` block's schema version, so a reader can tell what to expect of it. */
    private const val CALLVAULT_BLOCK_VERSION = 1

    /** ISO8601 with a numeric offset, matching BCR's `2023-07-19T21:53:08.931-04:00`. */
    private const val ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"

    /** The JSON text to write beside the recording, pretty-printed the way BCR's is. */
    fun build(input: Input): String {
        val root = JSONObject()
        root.put("timestamp_unix_ms", input.timestampUnixMs)
        // Locale.US for the pattern, default time zone for the offset: the format is machine-read, so
        // the digits must not become Arabic-Indic on an ar-EG phone, but the offset is genuinely
        // local — BCR's own example carries one.
        root.put("timestamp", SimpleDateFormat(ISO_8601, Locale.US).format(Date(input.timestampUnixMs)))
        root.putOrNull("package_name", input.packageName)
        root.putOrNull("direction", input.direction)
        // We do not read the SIM slot anywhere, and BCR's contract already has a word for that.
        root.put("sim_slot", input.simSlot ?: JSONObject.NULL)
        root.put("call_log_name", JSONObject.NULL)

        val call = JSONObject()
        call.putOrNull("phone_number", input.phoneNumber)
        // Null, not a re-derived guess. BCR means the country-specific formatting Android produced;
        // ours would be our own formatter's opinion wearing that field's name.
        call.put("phone_number_formatted", JSONObject.NULL)
        call.put("caller_name", JSONObject.NULL)
        call.putOrNull("contact_name", input.contactName)
        root.put("calls", JSONArray().put(call))

        val format = JSONObject()
        format.put("type", input.formatType)
        format.put("mime_type_container", input.mimeTypeContainer)
        format.put("mime_type_audio", input.mimeTypeAudio)
        format.put("parameter_type", input.parameterType)
        format.putOrNull("parameter", input.parameter)

        // Only the fields we actually measure. The frame counts, buffer statistics and hold/pause
        // flags are BCR's own encoder's bookkeeping; emitting invented numbers there would make a
        // reader's "audio was lost" diagnostics lie.
        val recording = JSONObject()
        recording.put("frames_total", JSONObject.NULL)
        recording.put("frames_encoded", JSONObject.NULL)
        recording.putOrNull("sample_rate", input.sampleRate)
        recording.putOrNull("channel_count", input.channelCount)
        recording.put("duration_secs_wall", JSONObject.NULL)
        recording.put("duration_secs_total", JSONObject.NULL)
        recording.putOrNull("duration_secs_encoded", input.durationSecsEncoded)
        recording.put("buffer_frames", JSONObject.NULL)
        recording.put("buffer_overruns", JSONObject.NULL)
        recording.put("was_ever_paused", JSONObject.NULL)
        recording.put("was_ever_holding", JSONObject.NULL)

        root.put("output", JSONObject().put("format", format).put("recording", recording))

        // CallVault's own measurements, under a namespaced key so a BCR reader ignores the whole block
        // rather than tripping over an unfamiliar top-level field. The speaker turns were separated
        // from the two capture channels before the mono downmix averaged them away (issue #50): a
        // server transcribing the mono Drive copy cannot recover who spoke when, but the app measured
        // it at capture time and hands it over here, matching the labels the app shows.
        val callvault = JSONObject()
        callvault.put("version", CALLVAULT_BLOCK_VERSION)
        // A null, not a missing key: "we did not work out which channel is yours" is a statement the
        // reader can act on (fall back to neutral sides), where an absent field is merely ambiguous.
        callvault.put("near_channel", input.nearChannel ?: JSONObject.NULL)
        // Absent, not null, when there are none (Shizuku, an older capture, a mono recording): the
        // key's presence is itself the signal that turns were measured at all.
        if (!input.speakerTurns.isNullOrBlank()) {
            callvault.put("speaker_turns", input.speakerTurns)
        }
        root.put("callvault", callvault)

        return root.toString(4)
    }

    /**
     * Puts [value], or an explicit JSON null when it is absent.
     *
     * `JSONObject.put(key, null)` **removes the key entirely**, which would silently turn "we do not
     * know the direction" into "there is no direction field" — a different statement, and one BCR's
     * format does not make. Every optional field goes through here.
     */
    private fun JSONObject.putOrNull(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
