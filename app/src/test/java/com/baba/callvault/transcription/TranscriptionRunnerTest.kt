/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.SpeakerTurnsEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The orchestration around whisper: state transitions, checkpointing, and what happens when one
 * recording in a batch cannot be transcribed.
 *
 * Deliberately tested apart from [TranscriptionEngine], which loads a native library a JVM test
 * cannot: the engine's own correctness is covered by the instrumented tests, while everything that
 * decides *whether and in what order* it runs is ordinary logic and belongs here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class TranscriptionRunnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearCatalog() = runBlocking {
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
    }

    @Test
    fun marks_a_recording_done_and_stores_its_segments() = runBlocking {
        // Arrange
        catalogued("ok.ogg")
        val runner = runnerReturning(
            listOf(TranscriptSegment(0, 1000, "שלום"), TranscriptSegment(1000, 2000, "עולם"))
        )

        // Act
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("ok.ogg"))

        // Assert
        val stored = transcript("ok.ogg")
        assertEquals(TranscriptState.DONE, stored!!.transcript.state)
        assertEquals(listOf("שלום", "עולם"), stored.segments.map { it.text })
        assertEquals(MODEL_ID, stored.transcript.modelId)
    }

    @Test
    fun marks_a_recording_failed_and_carries_on_with_the_rest() = runBlocking {
        // One undecodable file must not stall a whole night's backlog.
        catalogued("bad.ogg")
        catalogued("good.ogg")
        val runner = TranscriptionRunner(context) { _, uri, _, _, _ ->
            if (uri.toString().contains("bad")) error("cannot decode")
            listOf(TranscriptSegment(0, 1, "fine"))
        }

        // Act
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("bad.ogg", "good.ogg"))

        // Assert
        assertEquals(TranscriptState.FAILED, transcript("bad.ogg")!!.transcript.state)
        assertEquals(TranscriptState.DONE, transcript("good.ogg")!!.transcript.state)
        assertNotNull("a failure must say why", transcript("bad.ogg")!!.transcript.errorMessage)
    }

    @Test
    fun does_not_transcribe_a_recording_that_is_already_done() = runBlocking {
        // The checkpoint: a job killed after 25 minutes must resume at the next recording, not redo
        // the ones it already paid for.
        catalogued("already.ogg")
        var calls = 0
        val runner = TranscriptionRunner(context) { _, _, _, _, _ ->
            calls++
            listOf(TranscriptSegment(0, 1, "first pass"))
        }

        // Act — the same recording offered twice, as a resumed run would.
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("already.ogg"))
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("already.ogg"))

        // Assert
        assertEquals("re-transcribed work that was already finished", 1, calls)
    }

    @Test
    fun stops_between_recordings_when_asked_to() = runBlocking {
        // WorkManager stops long work; the batch must notice between recordings rather than
        // ploughing through an hour of CPU after being told to stop.
        catalogued("one.ogg")
        catalogued("two.ogg")
        var calls = 0
        val runner = TranscriptionRunner(context) { _, _, _, _, _ ->
            calls++
            listOf(TranscriptSegment(0, 1, "x"))
        }

        // Act
        runner.runBatch(
            MODEL_ID, MODEL_PATH, LANGUAGE, listOf("one.ogg", "two.ogg"),
            shouldStop = { calls >= 1 }
        )

        // Assert
        assertEquals(1, calls)
    }

    @Test
    fun an_empty_result_is_recorded_as_done_with_no_segments() = runBlocking {
        // Silence is a result, not a failure. Marking it FAILED would invite endless manual retries
        // of a call that genuinely has nothing in it.
        catalogued("silent.ogg")
        val runner = runnerReturning(emptyList())

        // Act
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("silent.ogg"))

        // Assert
        val stored = transcript("silent.ogg")!!
        assertEquals(TranscriptState.DONE, stored.transcript.state)
        assertTrue(stored.segments.isEmpty())
    }

    @Test
    fun skips_a_recording_that_has_vanished_from_the_catalog() = runBlocking {
        // Deleted between being queued and being reached. Nothing to decode, and nothing to record.
        var calls = 0
        val runner = TranscriptionRunner(context) { _, _, _, _, _ ->
            calls++
            emptyList()
        }

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("gone.ogg"))

        assertEquals(0, calls)
    }

    @Test
    fun stopping_a_run_leaves_the_recording_offering_to_transcribe_again() = runBlocking {
        // Tapping Stop cancels the coroutine mid-transcribe. Two wrong answers to avoid:
        //  - FAILED: the queue deliberately never re-offers a FAILED recording, so one Stop would
        //    exclude that call from every future automatic run until it was retried by hand. This is
        //    what actually happened on the OP12 on 2026-08-20 — the row went red after a Stop.
        //  - QUEUED: renders as the busy spinner, so in Manual mode the row would spin for ever with
        //    nothing running behind it.
        // Removing the row is the honest state: the row offers "Transcribe" again, and a recording
        // with no transcript row is exactly what the queue considers pending.
        catalogued("stopped.ogg")
        val runner = TranscriptionRunner(context) { _, _, _, _, _ ->
            throw kotlinx.coroutines.CancellationException("stopped")
        }

        // Act — the cancellation must propagate, so the worker can report it was stopped.
        var propagated = false
        try {
            runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("stopped.ogg"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = true
        }

        // Assert
        assertTrue("cancellation must not be swallowed", propagated)
        assertNull("a stopped recording must not keep a transcript row", transcript("stopped.ogg"))
        assertTrue(
            "a stopped recording must be offered again",
            TranscriptionQueue.pending(context).contains("stopped.ogg")
        )
    }

    @Test
    fun a_run_stopped_mid_recording_is_not_recorded_as_a_failure() = runBlocking {
        // The case that actually happens on the phone. `whisper_full` is a blocking native call that
        // coroutine cancellation cannot interrupt, so a stopped run does NOT surface as a
        // CancellationException — it surfaces as whatever the engine throws on the way out. Deciding
        // from the exception type therefore misses it, which is why the OP12 still showed a red
        // "failed" row after Stop even once CancellationException was handled. The stop flag is the
        // only reliable signal.
        catalogued("interrupted.ogg")
        var stopping = false
        val runner = TranscriptionRunner(context) { _, _, _, _, _ ->
            stopping = true                       // the worker has been told to stop…
            error("interrupted")                  // …and the native call dies with an ordinary error
        }

        // Act
        runner.runBatch(
            MODEL_ID, MODEL_PATH, LANGUAGE, listOf("interrupted.ogg"),
            shouldStop = { stopping }
        )

        // Assert
        assertNull("a stopped recording must not be left marked failed", transcript("interrupted.ogg"))
        assertTrue(
            "a stopped recording must be offered again",
            TranscriptionQueue.pending(context).contains("interrupted.ogg")
        )
    }

    @Test
    fun a_run_aborted_part_way_does_not_store_a_half_transcript() = runBlocking {
        // The nastiest of the three, because it looks like success. An aborted `whisper_full` RETURNS
        // NORMALLY with whatever it had decoded so far, so the attempt "succeeds" — storing that would
        // mark the call DONE with a transcript covering only its first few minutes, and DONE is never
        // re-offered by anything.
        catalogued("aborted.ogg")
        val runner = TranscriptionRunner(
            context,
            transcriber = { _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "only the first bit")) },
            wasAborted = { true }
        )

        // Act
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("aborted.ogg"))

        // Assert
        assertNull("a partial result must not be stored as a transcript", transcript("aborted.ogg"))
        assertTrue(
            "an aborted recording must be offered again",
            TranscriptionQueue.pending(context).contains("aborted.ogg")
        )
    }

    @Test
    fun a_stop_that_beats_the_worker_flag_is_still_not_a_failure() = runBlocking {
        // Stop aborts the engine *before* WorkManager marks the worker stopped. A run unwinding in
        // that window sees shouldStop() == false and used to be recorded as FAILED — which is what put
        // the red error icon on the row after a perfectly ordinary Stop.
        catalogued("raced.ogg")
        val runner = TranscriptionRunner(
            context,
            transcriber = { _, _, _, _, _ -> error("decode stopped") },
            wasAborted = { true }
        )

        // Act — note shouldStop stays false throughout, as it does in the real race.
        runner.runBatch(
            MODEL_ID, MODEL_PATH, LANGUAGE, listOf("raced.ogg"),
            shouldStop = { false }
        )

        // Assert
        assertNull("a stop must not be recorded as a failure", transcript("raced.ogg"))
    }

    @Test
    fun labels_segments_with_the_side_that_spoke_them() = runBlocking {
        // Arrange: the capture recorded who was talking, as it does on any stereo carrier call.
        catalogued("labelled.ogg")
        storeTurns("labelled.ogg", "0:A;2000:B")
        val runner = runnerReturning(
            listOf(TranscriptSegment(0, 1500, "שלום"), TranscriptSegment(2000, 3500, "היי"))
        )

        // Act
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("labelled.ogg"))

        // Assert: the neutral channel, never a name — who is who is decided at display time.
        assertEquals(listOf("A", "B"), transcript("labelled.ogg")!!.segments.map { it.speaker })
    }

    @Test
    fun stores_unlabelled_segments_when_the_capture_recorded_no_turns() = runBlocking {
        // A mono capture, a daemon too old to report turns, or any call recorded before speaker
        // tracking existed. Unlabelled is the ordinary case, not a failure.
        catalogued("plain.ogg")
        val runner = runnerReturning(listOf(TranscriptSegment(0, 1500, "שלום")))

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("plain.ogg"))

        assertEquals(listOf(null), transcript("plain.ogg")!!.segments.map { it.speaker })
    }

    @Test
    fun refuses_a_recording_that_is_too_long_to_decode() = runBlocking {
        // The per-call path: a call that has just ended is handed straight to the runner by name, so it
        // never passes the queue's filter. Attempting it decodes the whole recording into memory and
        // ends in a job that dies having produced nothing — after burning the CPU to get there.
        catalogued("marathon.ogg")
        var calls = 0
        val runner = TranscriptionRunner(
            context,
            audioDurationMs = { OVER_THE_LIMIT_MS },
            transcriber = { _, _, _, _, _ ->
                calls++
                emptyList()
            }
        )

        // Act
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("marathon.ogg"))

        // Assert
        assertEquals("a recording over the limit must not be attempted", 0, calls)
        assertNull("refusing is not a transcript, and not a failure either", transcript("marathon.ogg"))
    }

    @Test
    fun a_refused_recording_does_not_strand_the_row_a_tap_queued() = runBlocking {
        // A tap marks the row QUEUED before enqueuing, and QUEUED renders as the busy indicator. Left
        // in place it would spin for ever with nothing running behind it.
        catalogued("tapped.ogg")
        TranscriptDatabase.get(context).transcriptDao().upsertTranscript(
            TranscriptEntry(displayName = "tapped.ogg", state = TranscriptState.QUEUED)
        )
        val runner = TranscriptionRunner(
            context,
            audioDurationMs = { OVER_THE_LIMIT_MS },
            transcriber = { _, _, _, _, _ -> emptyList() }
        )

        // Act
        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("tapped.ogg"))

        // Assert
        assertNull("the row must go back to offering Transcribe", transcript("tapped.ogg"))
    }

    @Test
    fun transcribes_a_recording_whose_length_is_unknown() = runBlocking {
        // Deliberate, and the reason the limit is not applied to file size: a container that declares
        // no duration is ordinary, and refusing on "unknown" would silently drop short calls whose
        // metadata happens to be missing.
        catalogued("undated.ogg")
        val runner = TranscriptionRunner(
            context,
            audioDurationMs = { UNKNOWN_LENGTH_MS },
            transcriber = { _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "שלום")) }
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("undated.ogg"))

        assertEquals(TranscriptState.DONE, transcript("undated.ogg")!!.transcript.state)
    }

    @Test
    fun transcribes_a_recording_inside_the_limit() = runBlocking {
        // The guard must refuse long recordings without quietly costing us the ordinary ones.
        catalogued("ordinary.ogg")
        val runner = TranscriptionRunner(
            context,
            audioDurationMs = { UNDER_THE_LIMIT_MS },
            transcriber = { _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "שלום")) }
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("ordinary.ogg"))

        assertEquals(TranscriptState.DONE, transcript("ordinary.ogg")!!.transcript.state)
    }

    // ---- the file the user asked us to read and not to keep

    @Test
    fun a_transcribed_transcribe_only_import_loses_its_catalog_row_but_keeps_its_words() = runBlocking {
        // The whole point of the feature: the transcript is what was wanted, so it must outlive the
        // audio. Calling the ordinary delete here would run the transcript cascade and wipe the text
        // that had just been produced — which is the defect this path exists to avoid.
        val name = transcribeOnlyName()
        catalogued(name)
        val runner = runnerReturning(listOf(TranscriptSegment(0, 1000, "שלום")))

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf(name))

        val stored = transcript(name)
        assertEquals(TranscriptState.DONE, stored!!.transcript.state)
        assertEquals(listOf("שלום"), stored.segments.map { it.text })
        assertNull(
            "the catalog row must go, or it dangles with no copy behind it",
            RecordingCatalog.all(context).firstOrNull { it.displayName == name },
        )
    }

    @Test
    fun a_failed_transcribe_only_run_keeps_its_audio_and_its_row() = runBlocking {
        // Losing the audio AND the words is the one outcome that must be impossible. A failure is
        // retryable, and a retry needs the file.
        val name = transcribeOnlyName()
        catalogued(name)
        val runner = TranscriptionRunner(context) { _, _, _, _, _ -> error("cannot decode") }

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf(name))

        assertEquals(TranscriptState.FAILED, transcript(name)!!.transcript.state)
        assertNotNull(
            "a failed run must leave the audio catalogued",
            RecordingCatalog.all(context).firstOrNull { it.displayName == name },
        )
    }

    @Test
    fun a_stopped_transcribe_only_run_keeps_its_audio() = runBlocking {
        // A stop removes the transcript row, so nothing at all would account for the file if the
        // audio went too. It stays, and RecordingsRepository puts it back where it can be seen.
        val name = transcribeOnlyName()
        catalogued(name)
        val runner = TranscriptionRunner(
            context,
            transcriber = { _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "only the first bit")) },
            wasAborted = { true },
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf(name))

        assertNull("a partial result must not be stored", transcript(name))
        assertNotNull(
            "a stopped run must leave the audio catalogued",
            RecordingCatalog.all(context).firstOrNull { it.displayName == name },
        )
    }

    @Test
    fun a_transcribe_only_import_refused_for_length_keeps_its_audio() = runBlocking {
        val name = transcribeOnlyName()
        catalogued(name)
        val runner = TranscriptionRunner(
            context,
            audioDurationMs = { OVER_THE_LIMIT_MS },
            transcriber = { _, _, _, _, _ -> emptyList() },
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf(name))

        assertNotNull(
            "a refusal must leave the audio catalogued",
            RecordingCatalog.all(context).firstOrNull { it.displayName == name },
        )
    }

    @Test
    fun a_kept_import_is_never_deleted_by_a_finished_transcription() = runBlocking {
        val name = ImportedRecording.nameFor(IMPORTED_AT, label = "voice note", extension = ".ogg")
        catalogued(name)
        val runner = runnerReturning(listOf(TranscriptSegment(0, 1000, "שלום")))

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf(name))

        assertNotNull(
            "an import the user asked to keep must survive being transcribed",
            RecordingCatalog.all(context).firstOrNull { it.displayName == name },
        )
    }

    // ---- what the shade is told, which is the only sign a background run ever gives

    @Test
    fun reports_a_stored_transcript_once_it_is_really_stored() = runBlocking {
        // Reported after the words and the DONE row, never before: a notification that arrived first
        // would promise a transcript the user could tap through to and not find.
        catalogued("said.ogg")
        val reported = mutableListOf<Pair<String, TranscriptNotice.Outcome>>()
        val runner = TranscriptionRunner(
            context,
            onFinished = { name, outcome -> reported += name to outcome },
            transcriber = { _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "שלום")) },
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("said.ogg"))

        assertEquals(listOf("said.ogg" to TranscriptNotice.Outcome.Stored), reported)
        assertEquals(TranscriptState.DONE, transcript("said.ogg")!!.transcript.state)
    }

    @Test
    fun reports_a_failure_too() = runBlocking {
        // A failure was exactly as silent as a success: a red icon on a page nobody had been given a
        // reason to open.
        catalogued("broken.ogg")
        val reported = mutableListOf<Pair<String, TranscriptNotice.Outcome>>()
        val runner = TranscriptionRunner(
            context,
            onFinished = { name, outcome -> reported += name to outcome },
            transcriber = { _, _, _, _, _ -> error("cannot decode") },
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("broken.ogg"))

        assertEquals(listOf("broken.ogg" to TranscriptNotice.Outcome.Failed), reported)
    }

    @Test
    fun says_when_the_audio_went_with_the_transcript() = runBlocking {
        // The "Transcribe only" import: its file is deleted as the words land, so this notification
        // is not a convenience but the app's only account of what became of it.
        val name = transcribeOnlyName("shared note")
        catalogued(name)
        val reported = mutableListOf<Pair<String, TranscriptNotice.Outcome>>()
        val runner = TranscriptionRunner(
            context,
            onFinished = { n, outcome -> reported += n to outcome },
            transcriber = { _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "שלום")) },
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf(name))

        assertEquals(listOf(name to TranscriptNotice.Outcome.StoredAndAudioDeleted), reported)
    }

    @Test
    fun says_nothing_about_a_recording_it_refused_for_length() = runBlocking {
        // Nothing happened to it: no transcript, no failure, and a dialog has already told whoever
        // asked. A notification here would report an event the user has no way to act on.
        catalogued("marathon2.ogg")
        val reported = mutableListOf<String>()
        val runner = TranscriptionRunner(
            context,
            audioDurationMs = { OVER_THE_LIMIT_MS },
            onFinished = { name, _ -> reported += name },
            transcriber = { _, _, _, _, _ -> emptyList() },
        )

        runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("marathon2.ogg"))

        assertEquals(emptyList<String>(), reported)
    }

    @Test
    fun a_shade_that_cannot_be_written_to_does_not_lose_a_transcript() = runBlocking {
        // The reporting is the least important thing that happens on the success path: the words are
        // already stored, and a NotificationManager that throws must not turn a finished
        // transcription into a failed one.
        catalogued("noisy.ogg")
        val runner = TranscriptionRunner(
            context,
            onFinished = { _, _ -> error("no notification manager") },
            transcriber = { _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "שלום")) },
        )

        val transcribed = runner.runBatch(MODEL_ID, MODEL_PATH, LANGUAGE, listOf("noisy.ogg"))

        assertEquals(1, transcribed)
        assertEquals(TranscriptState.DONE, transcript("noisy.ogg")!!.transcript.state)
    }

    /**
     * [label] is a parameter because Robolectric keeps one transcripts database for the whole class:
     * two tests sharing a name would find the first one's DONE row, and the second would skip the
     * run it was written to exercise — silently, since skipping is what a resumed batch is meant to do.
     */
    private fun transcribeOnlyName(label: String = "voice note") = ImportedRecording.nameFor(
        importedAtMillis = IMPORTED_AT,
        label = label,
        extension = ".ogg",
        kind = ImportedRecording.Kind.TRANSCRIBE_ONLY,
    )

    private suspend fun storeTurns(name: String, encoded: String) {
        TranscriptDatabase.get(context).speakerTurnsDao().upsert(
            SpeakerTurnsEntry(
                displayName = name,
                turns = encoded,
                outgoing = true,
                observedMap = ChannelMap.UNKNOWN.key,
                updatedAt = 1L
            )
        )
    }

    private fun runnerReturning(segments: List<TranscriptSegment>) =
        TranscriptionRunner(context) { _, _, _, _, _ -> segments }

    private suspend fun catalogued(name: String) {
        RecordingCatalog.recordLocal(context, name, "content://local/$name".toUri(), 10L, 100L)
    }

    private suspend fun transcript(name: String) =
        TranscriptDatabase.get(context).transcriptDao().observe(name).first()

    private companion object {
        const val MODEL_ID = "small-q5_1"
        const val MODEL_PATH = "/models/small.bin"
        const val LANGUAGE = "he"

        /** 2026-09-16; only the shape of the stamp an import name carries matters. */
        const val IMPORTED_AT = 1_789_567_810_000L

        /**
         * Comfortably past [TranscriptionLengthLimit.MAX_MINUTES], **derived from it**.
         *
         * This was a literal 50 minutes and broke the day the limit moved past it — the second time a
         * test pinned this number instead of the behaviour. The limit is documented as a stopgap meant
         * to change; a test that fails when it changes fails for the one reason that is not a bug.
         */
        val OVER_THE_LIMIT_MS = (TranscriptionLengthLimit.MAX_MINUTES + 5L) * 60 * 1000
        const val UNDER_THE_LIMIT_MS = 5L * 60 * 1000

        /** What a container that declares no duration reports. */
        const val UNKNOWN_LENGTH_MS = 0L
    }
}
