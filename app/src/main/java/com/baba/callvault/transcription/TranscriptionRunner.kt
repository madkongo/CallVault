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
import android.os.SystemClock
import androidx.core.net.toUri
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.TranscribeOnlyAudio
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Turns audio into stored segments. Substituted in tests, because the real one loads a native
 * library a JVM test cannot.
 */
fun interface Transcriber {
    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelPath: String,
        language: String?,
        prompt: String?
    ): List<TranscriptSegment>
}

/**
 * Runs transcription over a list of recordings, one at a time, recording state as it goes.
 *
 * Everything here exists because the work is *slow*: a 30-minute call costs about 30 minutes of CPU
 * with the small model and over an hour with turbo. That single fact drives the design — progress is
 * committed per recording so an interrupted run resumes rather than restarts, one bad file cannot
 * stall the rest, and a stop request is honoured between recordings instead of an hour later.
 */
class TranscriptionRunner(
    private val context: Context,
    /** Whether the attempt that just ended was stopped rather than finished. Injected for tests. */
    private val wasAborted: () -> Boolean = { TranscriptionEngine.wasAborted() },
    /** One recording's length. Injected for tests, which have no real audio to measure. */
    private val audioDurationMs: (Uri) -> Long = { uri -> AudioDecoder.durationMs(context, uri) },
    // Last, so `TranscriptionRunner(context) { ... }` still reads as "a runner with this transcriber".
    private val transcriber: Transcriber = Transcriber(TranscriptionEngine::transcribe)
) {

    private val dao get() = TranscriptDatabase.get(context).transcriptDao()

    /**
     * Transcribes each of [displayNames] in turn.
     *
     * @param shouldStop consulted between recordings; when it returns true the batch ends early and
     *   whatever finished stays finished.
     * @param onProgress announced before each recording is started, so something on screen can say
     *   what is happening during a run that may last hours.
     * @return how many recordings were transcribed.
     */
    suspend fun runBatch(
        modelId: String,
        modelPath: String,
        language: String?,
        displayNames: List<String>,
        shouldStop: () -> Boolean = { false },
        onProgress: suspend (completed: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): Int {
        var transcribed = 0
        var reached = 0

        for (displayName in displayNames) {
            if (shouldStop()) {
                AppLogger.i(TAG, "Stopping after $transcribed recording(s); the rest stay queued")
                break
            }
            // Count recordings reached rather than transcribed: a skipped or failed one still moves
            // the run forward, and a counter that stalls on a bad file looks like a hang.
            onProgress(reached, displayNames.size, displayName)
            reached++
            if (runOne(modelId, modelPath, language, displayName, shouldStop)) transcribed++
        }

        return transcribed
    }

    /**
     * Transcribes one recording. Returns true when it finished and was stored.
     *
     * @param shouldStop consulted only if the attempt throws, to tell an interruption apart from a
     *   genuine failure. It cannot be inferred from the exception: `whisper_full` is a blocking
     *   native call that coroutine cancellation cannot interrupt, so a stopped run surfaces as
     *   whatever the engine throws on the way out, not as a [CancellationException].
     */
    suspend fun runOne(
        modelId: String,
        modelPath: String,
        language: String?,
        displayName: String,
        shouldStop: () -> Boolean = { false }
    ): Boolean {
        // The checkpoint. A resumed run re-offers everything it was given, so finished work must be
        // recognised and skipped rather than paid for twice.
        if (dao.findTranscript(displayName)?.state == TranscriptState.DONE) {
            AppLogger.i(TAG, "$displayName is already transcribed; skipping")
            return false
        }

        val uri = localUriFor(displayName) ?: run {
            // Deleted between being queued and being reached. Nothing to decode and nothing to say.
            AppLogger.i(TAG, "$displayName is no longer in the catalog; skipping")
            return false
        }

        // Length before anything is marked or decoded. Transcription decodes the whole recording into
        // memory before the model sees any of it, so past a point the job dies — after a long wait —
        // having produced nothing. Every route into transcription arrives here: the nightly sweep, the
        // per-call run when a call ends, and a tap. Refusing at this one funnel is what stops the check
        // from being a thing three callers each have to remember, which is how the automatic paths came
        // to attempt 50-minute recordings while only the tap refused them.
        val audioMs = audioDurationMs(uri)
        if (TranscriptionLengthLimit.isTooLong(audioMs)) {
            // Left with no row at all, as a stopped run is. FAILED would bar the recording from every
            // future automatic run — including the ones that become possible when decoding is chunked
            // and this limit goes away — and QUEUED, which a tap writes before enqueuing, would spin on
            // the busy indicator for ever with nothing behind it.
            dao.deleteFor(displayName)
            AppLogger.w(
                TAG,
                "Skipped $displayName: ${audioMs / MS_PER_MINUTE} minutes is over the " +
                    "${TranscriptionLengthLimit.MAX_MINUTES}-minute transcription limit"
            )
            return false
        }

        mark(displayName, TranscriptState.RUNNING, modelId, language)

        // Monotonic, like every other duration in this app. The wall clock can be corrected by NTP
        // or changed by the user mid-run, and this number is not merely displayed — it is divided by
        // the audio length and stored as this phone's permanent speed, so a clock jump would be
        // learned as a property of the hardware and quoted back for the next dozen runs.
        val startedAt = SystemClock.elapsedRealtime()
        // Named before the words are decoded, so a brand or a contact is spelled rather than
        // guessed at. Best-effort: no glossary and no resolvable name simply means no prompt.
        val prompt = runCatching { promptFor(displayName) }.getOrNull()
        // Claimed for exactly as long as the engine is busy with this recording, so a delete
        // arriving mid-run knows there is something to abandon — see [TranscriptionInFlight].
        TranscriptionInFlight.claim(displayName)
        val attempt = try {
            runCatching { transcriber.transcribe(context, uri, modelPath, language, prompt) }
        } finally {
            TranscriptionInFlight.release(displayName)
        }

        // A stop is not a result, and neither the exception nor `shouldStop` can be trusted to say so:
        //  - an aborted `whisper_full` returns NORMALLY with a partial result, so a stop can arrive
        //    dressed as success. Storing it would mark the call DONE with a transcript of its first
        //    few minutes, and nothing ever re-offers a DONE recording.
        //  - Stop aborts the engine before WorkManager marks the worker stopped, so a run unwinding in
        //    that window sees shouldStop() == false and used to be recorded as FAILED — the red error
        //    icon that appeared after an ordinary Stop.
        // Removing the row restores exactly the state before the run: the row offers "Transcribe"
        // again, and a recording with no row is what the queue counts as pending.
        val error = attempt.exceptionOrNull()
        if (wasAborted() || shouldStop() || error is CancellationException) {
            // NonCancellable: by now the coroutine is usually cancelled, and a plain suspend call
            // would abandon the very cleanup this exists to do.
            withContext(NonCancellable) { dao.deleteFor(displayName) }
            AppLogger.i(TAG, "Transcription of $displayName was stopped; left for a later run")
            if (error is CancellationException) throw error
            return false
        }

        return attempt.fold(
            onSuccess = { segments ->
                dao.replaceSegments(displayName, segments.labelled(displayName))
                mark(displayName, TranscriptState.DONE, modelId, language)
                // A file the user imported to read rather than to keep loses its audio HERE, and
                // nowhere else — after the words and the DONE row are both written, on the success
                // path alone. Every other way out of this function (a stop, an abort, a failure, a
                // refusal for length) has already returned, so none of them can destroy the only
                // copy of a recording in exchange for nothing.
                //
                // NonCancellable for the same reason the abort cleanup above is: by the time this
                // runs the worker may already be stopping, and a half-done delete would leave a file
                // with no catalog row — invisible to the app and to every sweep that walks the
                // catalog rather than the folder.
                withContext(NonCancellable) {
                    TranscribeOnlyAudio.deleteAfterTranscript(context, displayName)
                }
                // What it really cost on this phone, so the next estimate is measured rather than
                // inherited from whatever hardware the published figure came from.
                recordSpeed(modelId, audioMs, SystemClock.elapsedRealtime() - startedAt)
                // Count, never content: a transcript is the substance of a private call.
                AppLogger.i(TAG, "Transcribed $displayName (${segments.size} segment(s))")
                true
            },
            onFailure = { failure ->
                AppLogger.w(TAG, "Failed to transcribe $displayName: ${failure.message}")
                mark(displayName, TranscriptState.FAILED, modelId, language, failure.message)
                false
            }
        )
    }

    /** Folds one run's observed cost into this phone's stored figures for [modelId]. */
    private fun recordSpeed(modelId: String, audioMs: Long, elapsedMs: Long) {
        // The two halves of a run, learned separately because they scale differently: setup costs
        // the same whatever the call's length, and only the rest is per-second work. A run that
        // never reported its setup leaves this at zero, which charges the whole run as work — the
        // old behaviour, and pessimistic rather than wrong.
        val setupMs = TranscriptionEngine.lastSetupMs.coerceIn(0L, elapsedMs)
        val workMs = elapsedMs - setupMs

        val measured = TranscriptionEstimate.measure(audioMs, workMs) ?: return
        val prefs = AppPreferences(context)
        // Speeds measured under a different thread policy describe a machine that no longer exists.
        // Discarded here rather than averaged away, which would quote a wrong estimate on each of
        // the next several runs while it converged.
        //
        // The count the run ACTUALLY used, not a fresh reading: preferredThreadCount() derives from
        // online cores, which a phone varies with thermal state, so asking again here could report a
        // policy no run ever used and wipe every stored figure on the strength of it.
        prefs.setRtfCalibrationThreads(
            TranscriptionEngine.lastThreadCount.takeIf { it > 0 }
                ?: TranscriptionEngine.preferredThreadCount()
        )
        val model = TranscriptionModel.fromId(modelId)
        val blended = TranscriptionEstimate.blend(
            stored = prefs.getTranscriptionRtf(modelId),
            measured = measured,
            // The MODEL'S published figure, never `measured`. Passing the measurement here handed
            // `blend` its own rejected value as the safe default, so an impossible reading — a
            // half-second clip that paid a full model load, say — was stored as this phone's
            // permanent speed and quoted back as "about 3 hours" for a two-minute call (issue #26).
            fallback = model?.realTimeFactor ?: TranscriptionEstimate.DEFAULT_RTF,
        )
        prefs.setTranscriptionRtf(modelId, blended)

        // Only learned when the engine actually reported it; a zero means "not timed", and storing
        // that as a fixed cost of nothing would under-quote every short call from here on.
        val blendedLoad = if (setupMs > 0L) {
            TranscriptionEstimate.blendLoadMs(
                stored = prefs.getTranscriptionLoadMs(modelId),
                measured = setupMs,
                fallback = model?.seedLoadMs ?: setupMs,
            ).also { prefs.setTranscriptionLoadMs(modelId, it) }
        } else {
            prefs.getTranscriptionLoadMs(modelId)
        }

        AppLogger.i(
            TAG,
            "Measured %.2fx real time for %s (setup %d ms, work %d ms over %d ms of audio); stored %.2fx, load %s"
                .format(measured, modelId, setupMs, workMs, audioMs, blended, blendedLoad?.toString() ?: "unmeasured")
        )
    }

    /**
     * The words to expect for [displayName]: who the call is with.
     *
     * The contact is looked up the same way the list does it, so the prompt names the person by the
     * name shown on screen rather than by a number.
     */
    private suspend fun promptFor(displayName: String): String? {
        val contact = RecordingsRepository.listRecordings(context)
            .firstOrNull { it.displayName == displayName }
            ?.contactName
        return TranscriptionPrompt.build(contact)
    }

    private suspend fun localUriFor(displayName: String): Uri? =
        RecordingCatalog.all(context)
            .firstOrNull { it.displayName == displayName }
            ?.localUri
            ?.toUri()

    private suspend fun mark(
        displayName: String,
        state: TranscriptState,
        modelId: String,
        language: String?,
        errorMessage: String? = null
    ) {
        dao.upsertTranscript(
            TranscriptEntry(
                displayName = displayName,
                state = state,
                modelId = modelId,
                language = language,
                updatedAt = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
        )
    }

    /**
     * Turns whisper's segments into rows, attributing each to the side that spoke it where the
     * capture recorded who was talking.
     *
     * The turns are read once per recording rather than per segment, and their absence is the
     * ordinary case rather than an error: a mono capture, a daemon too old to report them, or any
     * call recorded before speaker tracking existed simply yields unlabelled rows — which is what
     * every transcript looked like until now.
     *
     * Labels are the neutral `A`/`B`, never a name. Which side is the user is resolved when the
     * transcript is displayed, so a mapping learned tomorrow improves the transcripts stored today
     * and a mapping lost never leaves a wrong name behind.
     */
    private suspend fun List<TranscriptSegment>.labelled(
        displayName: String
    ): List<TranscriptSegmentEntry> {
        val turns = SpeakerLabeller.decode(SpeakerTurnsRepository.turnsFor(context, displayName))
        val speakers = SpeakerLabeller.labelAll(turns, map { it.startMs to it.endMs })

        return mapIndexed { index, segment ->
            TranscriptSegmentEntry(
                displayName = displayName,
                startMs = segment.startMs,
                endMs = segment.endMs,
                text = segment.text,
                speaker = speakers.getOrNull(index)
            )
        }
    }

    private companion object {
        const val TAG = "CV:TranscriptionRunner"

        /** Only ever used to say a refused length out loud in minutes. */
        const val MS_PER_MINUTE = 60_000L
    }
}
