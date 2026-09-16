/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baba.callvault.data.recordings.ImportableAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the app's own decoder actually does with each format someone might import.
 *
 * Import lets the user hand CallVault a file it did not record, and the only thing that makes such a
 * file worth importing is that it can be transcribed. Whether it can is not a matter of opinion: it
 * depends on whether this device has a MediaCodec decoder for the container's codec, and on whether
 * that decoder emits the 16-bit PCM [AudioDecoder] insists on. Both are answers only a device can
 * give, which is why this is an instrumented test and not a unit test.
 *
 * **Two claims are under test, and they are different claims.**
 *
 *  - Every extension [ImportableAudio] accepts really does decode here, so the picker's filter is
 *    not offering the user files that will disappoint them.
 *  - The decode probe refuses a file the extension list cannot judge, because a name is only a
 *    claim. A source called `.mp3` that holds anything else passes every check that can be made
 *    without opening it, and without the probe would be copied in, catalogued, listed, and found
 *    un-transcribable only at the end of a run the user waited for.
 *
 * **Measured on the emulator (AOSP 16, arm64) on 2026-09-16**, and the plan's expectation was
 * wrong in two places:
 *
 * | fixture | container / codec | decoded | notes |
 * |---|---|---|---|
 * | `probe.opus` | Ogg / Opus 48 kHz | yes | 16216 samples for 1.000 s |
 * | `probe.ogg` | Ogg / Opus 48 kHz | yes | 16216 samples |
 * | `probe.m4a` | MP4 / AAC 44.1 kHz | yes | 16346 samples |
 * | `probe.mp3` | MP3 44.1 kHz | yes | 16000 samples, duration read as 1044 ms |
 * | `probe.wav` | WAVE / PCM s16le | **yes** | plan expected this to fail |
 * | `probe_float32.wav` | WAVE / PCM f32le | **yes** | the codec converts to 16-bit for us |
 *
 * WAV was the format the plan flagged and it decodes: `c2.android.raw.decoder` is present, and
 * PCM/WAVE decoding is in any case a CDD requirement for handhelds. The float variant decodes too,
 * because MediaCodec's default output encoding is 16-bit and the raw decoder converts rather than
 * handing back floats — so [AudioDecoder]'s PCM-encoding check never fires on it. Both are recorded
 * here rather than in a commit message because the next person to wonder will wonder about exactly
 * these two.
 *
 * The fixtures are one second of a 440 Hz sine, generated with ffmpeg and committed beside this
 * test — a few kilobytes each, in the androidTest source set alone, so nothing reaches the shipped
 * APK. Real audio is deliberately not used: the question here is "does the codec exist and does the
 * decode produce the right amount of audio", which a tone answers exactly as well and can be
 * committed to a public repository.
 */
@RunWith(AndroidJUnit4::class)
class AudioImportFormatProbe {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** One second of tone, so a correct decode is ~16000 samples at [AudioDecoder.TARGET_SAMPLE_RATE]. */
    private val expectedSamples = AudioDecoder.TARGET_SAMPLE_RATE

    /** Copies a committed fixture out of the test APK's assets, where MediaExtractor can open it. */
    private fun fixture(name: String): File {
        val out = File(context.cacheDir, "import-probe-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open("import-probe/$name").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    /** What happened to one fixture, as a line for the log and an assertion for the build. */
    private data class Outcome(val name: String, val durationMs: Long, val samples: Int, val failure: String?)

    private fun probe(name: String): Outcome {
        val file = fixture(name)
        val uri = Uri.fromFile(file)
        val durationMs = runCatching { AudioDecoder.durationMs(context, uri) }.getOrDefault(-1L)
        return runCatching {
            val audio = AudioDecoder.decodeToMono16k(context, uri)
            Outcome(name, durationMs, audio.size, null)
        }.getOrElse { e ->
            Outcome(name, durationMs, 0, "${e.javaClass.simpleName}: ${e.message}")
        }.also {
            // Logged whatever the verdict, because the reason a format is refused is the thing a
            // later session will want and the assertion message only carries the first failure.
            Log.i(TAG, "probe $name: durationMs=${it.durationMs} samples=${it.samples} failure=${it.failure}")
        }
    }

    private fun assertDecodes(fixtureName: String) {
        assertNotNull(
            "$fixtureName is probed but ImportableAudio would refuse it, so no user could ever supply one",
            ImportableAudio.storedAs(fixtureName, null),
        )
        val outcome = probe(fixtureName)
        assertEquals("$fixtureName failed to decode", null, outcome.failure)
        assertTrue(
            "$fixtureName declared no duration; the import would be catalogued with no length",
            outcome.durationMs > 0L,
        )
        val ratio = outcome.samples.toDouble() / expectedSamples
        assertTrue(
            "$fixtureName decoded ${outcome.samples} samples, expected about $expectedSamples",
            ratio in (1.0 - TOLERANCE)..(1.0 + TOLERANCE),
        )
    }

    @Test
    fun opus_in_an_ogg_container_decodes() = assertDecodes("probe.opus")

    @Test
    fun ogg_decodes() = assertDecodes("probe.ogg")

    @Test
    fun m4a_decodes() = assertDecodes("probe.m4a")

    @Test
    fun mp3_decodes() = assertDecodes("probe.mp3")

    @Test
    fun sixteen_bit_wav_decodes() = assertDecodes("probe.wav")

    /**
     * A 32-bit-float WAV, which was expected to be the awkward one and is not.
     *
     * MediaCodec's default output encoding is 16-bit — float only comes back if the client asks for
     * it on configure, which [AudioDecoder] does not — so the raw decoder converts and the
     * PCM-encoding check never fires. Asserted rather than merely logged so that a future platform
     * change to that default is caught here, where it is one failing test, rather than in the field
     * as noise in somebody's transcript.
     */
    @Test
    fun float32_wav_is_converted_to_sixteen_bit_for_us() = assertDecodes("probe_float32.wav")

    /**
     * The file that proves the extension list cannot be the only gate.
     *
     * It is called `.mp3` and it is not audio. Every check that can be made without opening it —
     * the name, the provider's type, the byte count — says yes, so [ImportableAudio] accepts it and
     * is right to. Only opening it settles the question, which is why [AudioImport] copies first and
     * then decodes before it catalogues anything.
     */
    @Test
    fun a_file_that_only_claims_to_be_audio_is_refused_by_the_decoder() {
        assertNotNull(
            "the name gate is supposed to accept this; the decoder is what turns it down",
            ImportableAudio.storedAs("probe_notaudio.mp3", null),
        )
        val outcome = probe("probe_notaudio.mp3")
        Log.i(TAG, "not-audio verdict: $outcome")
        assertFalse(
            "a file with no audio in it decoded anyway, so the import probe would catalogue it",
            outcome.failure == null && outcome.samples > 0,
        )
    }

    private companion object {
        private const val TAG = "CV:ImportProbe"

        /** How far from one second a decode may land and still count as right. */
        private const val TOLERANCE = 0.25
    }
}
