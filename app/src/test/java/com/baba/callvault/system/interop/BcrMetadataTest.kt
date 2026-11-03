/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.interop

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The point of this file is interop, so the tests are about the *contract*: exact key names, and
 * explicit nulls rather than missing keys. A plausible file with the wrong keys is worse than none,
 * because the reader shows blanks instead of failing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BcrMetadataTest {

    private fun input(
        phoneNumber: String? = "+11234567890",
        contactName: String? = "John Doe",
        direction: String? = "in",
        speakerTurns: String? = "0:A;1200:+;3400:-",
        nearChannel: String? = "B",
        simSlot: Int? = null,
    ) = BcrMetadata.Input(
        timestampUnixMs = 1689817988931L,
        packageName = "com.android.phone",
        direction = direction,
        phoneNumber = phoneNumber,
        contactName = contactName,
        formatType = "OGG/Opus",
        mimeTypeContainer = "audio/ogg",
        mimeTypeAudio = "audio/opus",
        parameterType = "bitrate",
        parameter = 24000,
        sampleRate = 48000,
        channelCount = 1,
        durationSecsEncoded = 12.5,
        speakerTurns = speakerTurns,
        nearChannel = nearChannel,
        simSlot = simSlot,
    )

    @Test
    fun `the SIM slot is written when known and an explicit null otherwise`() {
        assertEquals(2, JSONObject(BcrMetadata.build(input(simSlot = 2))).getInt("sim_slot"))
        val unknown = JSONObject(BcrMetadata.build(input()))
        assertTrue(unknown.has("sim_slot"))
        assertTrue(unknown.isNull("sim_slot"))
    }

    @Test
    fun `writes the exact top-level keys BCR documents`() {
        val json = JSONObject(BcrMetadata.build(input()))

        listOf(
            "timestamp_unix_ms", "timestamp", "package_name", "direction",
            "sim_slot", "call_log_name", "calls", "output"
        ).forEach { assertTrue("missing top-level key `$it`", json.has(it)) }
    }

    @Test
    fun `writes the guaranteed fields with real values`() {
        // BCR guarantees only these three groups exist. Ours must never emit null for them.
        val json = JSONObject(BcrMetadata.build(input()))

        assertEquals(1689817988931L, json.getLong("timestamp_unix_ms"))
        val format = json.getJSONObject("output").getJSONObject("format")
        assertEquals("OGG/Opus", format.getString("type"))
        assertEquals("audio/ogg", format.getString("mime_type_container"))
        assertEquals("audio/opus", format.getString("mime_type_audio"))
        assertEquals("bitrate", format.getString("parameter_type"))
        assertEquals(24000, format.getInt("parameter"))
    }

    @Test
    fun `the timestamp is ISO8601 with an offset`() {
        val timestamp = JSONObject(BcrMetadata.build(input())).getString("timestamp")

        // Shape, not the exact instant: the offset is genuinely the device's, as in BCR's own example.
        assertTrue(
            "not ISO8601 with offset: $timestamp",
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}([+-]\d{2}:\d{2}|Z)$""").matches(timestamp)
        )
    }

    @Test
    fun `an unknown field is an explicit null, never a missing key`() {
        // The trap this pins: JSONObject.put(key, null) DELETES the key. That would turn "we do not
        // know the number" into "there is no phone_number field" — a different statement, and one
        // BCR's format does not make.
        val json = JSONObject(BcrMetadata.build(input(phoneNumber = null, contactName = null)))
        val call = json.getJSONArray("calls").getJSONObject(0)

        assertTrue("phone_number key was dropped", call.has("phone_number"))
        assertTrue("contact_name key was dropped", call.has("contact_name"))
        assertTrue(call.isNull("phone_number"))
        assertTrue(call.isNull("contact_name"))
    }

    @Test
    fun `never invents encoder statistics it did not measure`() {
        // Emitting a made-up frames_encoded would make a reader's "audio was lost" check lie.
        val recording = JSONObject(BcrMetadata.build(input()))
            .getJSONObject("output").getJSONObject("recording")

        assertTrue(recording.isNull("frames_total"))
        assertTrue(recording.isNull("frames_encoded"))
        assertTrue(recording.isNull("buffer_overruns"))
        // But it does report what it genuinely knows.
        assertEquals(48000, recording.getInt("sample_rate"))
        assertEquals(1, recording.getInt("channel_count"))
        assertEquals(12.5, recording.getDouble("duration_secs_encoded"), 0.001)
    }

    @Test
    fun `calls is an array, because BCR allows several parties`() {
        val calls = JSONObject(BcrMetadata.build(input())).getJSONArray("calls")

        assertEquals(1, calls.length())
        assertEquals("John Doe", calls.getJSONObject(0).getString("contact_name"))
    }

    @Test
    fun `a null direction survives as null rather than as the string null`() {
        val json = JSONObject(BcrMetadata.build(input(direction = null)))

        assertTrue(json.has("direction"))
        assertTrue(json.isNull("direction"))
    }

    @Test
    fun `the callvault block carries the measured turns and near channel`() {
        // The whole point of issue #50: a server transcribing the mono Drive copy gets the speaker
        // turns and the near-side mapping the app already measured, under its own namespaced key.
        val callvault = JSONObject(BcrMetadata.build(input())).getJSONObject("callvault")

        assertEquals(1, callvault.getInt("version"))
        assertEquals("0:A;1200:+;3400:-", callvault.getString("speaker_turns"))
        assertEquals("B", callvault.getString("near_channel"))
    }

    @Test
    fun `an app call reports the near party on channel A`() {
        // VoipCaptureSession builds its stereo with the near party on the left (channel A), so an app
        // call's near_channel is known by construction even when nothing had to be learned.
        val callvault = JSONObject(BcrMetadata.build(input(nearChannel = "A"))).getJSONObject("callvault")

        assertEquals("A", callvault.getString("near_channel"))
    }

    @Test
    fun `no turns means the speaker_turns key is absent, not an empty string`() {
        // Its presence is the signal that turns were measured at all; Shizuku, an older daemon or a
        // mono capture leave none, and a reader must see nothing rather than empty turns.
        val callvault = JSONObject(BcrMetadata.build(input(speakerTurns = null))).getJSONObject("callvault")

        assertTrue("speaker_turns should be absent when there are none", !callvault.has("speaker_turns"))
        // The block still exists and still names its version.
        assertEquals(1, callvault.getInt("version"))
    }

    @Test
    fun `an unknown near channel is an explicit null, never a missing key`() {
        // A carrier call whose mapping is not trusted: "we did not work out which side is yours" is a
        // statement the reader can act on (fall back to neutral sides), not an absent field.
        val callvault = JSONObject(BcrMetadata.build(input(nearChannel = null))).getJSONObject("callvault")

        assertTrue("near_channel key was dropped", callvault.has("near_channel"))
        assertTrue(callvault.isNull("near_channel"))
    }

    @Test
    fun `the callvault block never disturbs the BCR fields a reader relies on`() {
        // The block is additive: a BCR-only reader ignores it and still finds everything it expects.
        val json = JSONObject(BcrMetadata.build(input()))

        listOf(
            "timestamp_unix_ms", "timestamp", "package_name", "direction",
            "sim_slot", "call_log_name", "calls", "output"
        ).forEach { assertTrue("missing top-level key `$it`", json.has(it)) }
        assertEquals(1689817988931L, json.getLong("timestamp_unix_ms"))
        assertTrue(json.getJSONObject("output").has("format"))
        assertTrue(json.getJSONObject("output").has("recording"))
    }
}
