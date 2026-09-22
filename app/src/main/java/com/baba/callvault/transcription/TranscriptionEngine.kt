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
import androidx.annotation.VisibleForTesting
import com.baba.callvault.server.speakers.OfflineSpeakerLabeller
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single entry point for turning a recording into text.
 *
 * Everything is serialised onto one dedicated thread, for two independent reasons: whisper.cpp
 * forbids concurrent access to a context at all, and a second concurrent job would only fight the
 * first for cores it is already saturating — two jobs would take roughly twice as long each rather
 * than finishing any sooner.
 *
 * Runs in the app process. It must never be invoked from the privileged recorder daemon.
 */
object TranscriptionEngine {

    /**
     * Whether a recording is being transcribed right now.
     *
     * Exists so background work can stand aside. Transcription saturates the CPU for minutes, and
     * anything else decoding audio at the same time both slows it down and poisons the timing the
     * estimate calibrates from — a factor learned while something else was stealing cores is wrong
     * for every run afterwards.
     */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * What the last run spent getting ready before it looked at any audio: loading the model, and
     * unpacking the VAD model on the first run of the app's life.
     *
     * Reported rather than inferred, because it is the one part of a run whose cost does not scale
     * with the length of the call — and folding it into the per-second rate is what made a
     * ten-second clip measure as an impossibly slow phone (issue #26). Zero when the last run never
     * reached setup, which callers must read as "unknown" rather than as "instant".
     */
    @Volatile
    var lastSetupMs: Long = 0L
        private set

    /**
     * The thread count the last run actually used.
     *
     * Recorded because `preferredThreadCount()` reads *online* cores, which a phone varies with
     * thermal state — so asking again after the run can report a policy no run ever used, and
     * discard every stored speed on the strength of it.
     */
    @Volatile
    var lastThreadCount: Int = 0
        private set


    private const val TAG = "CV:Transcribe"

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cv-transcribe").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Threads to hand whisper.cpp: every core.
     *
     * Measured on the OP12 (SM8650, 6 performance + 2 efficiency cores) with `large-v3-turbo-q5_0` —
     * 4 threads 140.4 s, 6 threads 99.2 s, 8 threads 95.9 s. Using the efficiency cluster too was
     * fastest, so the "prefer performance cores" rule this was originally designed with is
     * deliberately NOT implemented. Do not reintroduce it without fresh measurements.
     *
     * Clamped to at least 1: `availableProcessors()` may change between calls and has been observed
     * returning nonsense, and whisper.cpp divides work by whatever it is handed.
     */
    /**
     * How many threads to give whisper, from a core count.
     *
     * **Not every core.** Phone CPUs are heterogeneous — the OP12's eight are six performance cores
     * and two efficiency cores — and ggml synchronises its threads at every layer. A thread pinned
     * to a little core therefore does not add its share of work; it makes the other five wait for it
     * at each barrier. Using all eight measured slower than using the big ones alone, and burned the
     * little cores' power for the privilege.
     *
     * Two are left out, which lands on exactly the performance cores of the common 6+2 phones, and
     * degrades sensibly elsewhere: a 4-core phone gets 2, a single-core one still gets 1. Capped at
     * six because past that the gains flatten while contention with the rest of the phone does not.
     *
     * Changing this changes how long a run takes, so it invalidates the speed this device has
     * already calibrated — see [TranscriptionEstimate].
     */
    fun threadCountFor(availableProcessors: Int): Int =
        (availableProcessors - RESERVED_CORES).coerceIn(1, MAX_THREADS)

    /** Left for the OS, the UI and whatever else the phone is doing while this runs. */
    private const val RESERVED_CORES = 2

    /** Past this, ggml's gains flatten while contention with the rest of the phone does not. */
    private const val MAX_THREADS = 6

    fun preferredThreadCount(): Int = threadCountFor(Runtime.getRuntime().availableProcessors())

    /**
     * Asks a run in progress to stop, from any thread.
     *
     * Best-effort by design: it returns immediately, and the run ends shortly afterwards with
     * whatever it had. Safe to call when nothing is running — the flag is cleared before each run.
     * Wrapped because it touches the native library, which may not have loaded on a device where the
     * ABI is unsupported, and failing to stop must never crash the caller.
     */
    fun requestAbort() {
        // Two halves, because a run has two uninterruptible phases: decoding (Kotlin, reads this
        // flag) and whisper itself (native, reads its own).
        abortRequested.set(true)
        runCatching { WhisperNative.requestAbort() }
            .onFailure { AppLogger.w(TAG, "Abort request failed: ${it.message}") }
    }

    /**
     * How far through the current recording whisper is, 0-100. Zero when nothing is running.
     *
     * Gated on [whisperActive] rather than read straight from native, because the native counter is
     * a global that survives the run that filled it. It is zeroed when `whisper_full` is entered —
     * but loading the model and decoding the audio happen first and take seconds, and a poller
     * asking during that window used to get the PREVIOUS run's figure. Re-transcribing therefore
     * opened at the last run's percentage rather than at nothing, which reads as a job already
     * nearly done. The doc above promised zero; only this keeps the promise.
     */
    fun progressPercent(): Int {
        if (!whisperActive) return 0
        // Scaled across the whole call, not the current chunk. whisper only knows about the slice it
        // was handed, so on a long call an unscaled reading would climb to 100% and start again at
        // every seam — which reads as a stall, or worse, as a finished job that then keeps running.
        val total = chunkCount
        if (total <= 1) return WhisperNative.progressPercent()
        val done = chunkIndex * 100
        return ((done + WhisperNative.progressPercent()) / total).coerceIn(0, 100)
    }

    /** How many passes this run takes, and which one is under way. Both 0 when nothing is running. */
    @Volatile private var chunkCount: Int = 0
    @Volatile private var chunkIndex: Int = 0

    /** True only while native decoding is actually under way — see [progressPercent]. */
    @Volatile
    private var whisperActive: Boolean = false

    /** Set by [requestAbort], cleared at the start of every run so a stale abort cannot kill the next. */
    private val abortRequested = AtomicBoolean(false)

    /**
     * Whether the run that just ended was stopped rather than finished.
     *
     * The authority on that question, because the two obvious alternatives are both wrong:
     *  - the exception does not say. An aborted `whisper_full` returns **normally**, so a stop looks
     *    exactly like success — and storing that would leave a half transcript.
     *  - `isStopped` may not be true yet. Stop aborts the engine before WorkManager marks the worker
     *    stopped, so a run unwinding in that window looks like a genuine failure.
     */
    fun wasAborted(): Boolean = abortRequested.get()

    /**
     * Clears the abort flag without starting a run.
     *
     * Production never needs this — [transcribe] clears it as each run begins — but a test that
     * asserts an abort *did not* happen must not inherit one set by the test before it.
     */
    @VisibleForTesting
    fun clearAbortForTest() {
        abortRequested.set(false)
    }

    /**
     * Transcribes [uri] using the model at [modelPath].
     *
     * @param language ISO code such as "he", or null to auto-detect. Passing the wrong language is
     *   not a soft failure — decoding Hebrew as English yields confident nonsense.
     * @return segments in order, or **empty when nothing was recognised**. Callers must surface that
     *   as "no speech detected" rather than as a successful empty transcript; reporting only what was
     *   actually observed is the same rule the VoIP capture path follows.
     */
    /**
     * Transcribes one buffer of 16 kHz mono float, on an already-loaded model.
     *
     * **Only the chunking benchmark uses this.** Production transcribes a whole recording through
     * [transcribe]; the orchestration that would cut a call into passes deliberately does not live in
     * this class, because the first attempt at it shipped and made real transcripts worse — timestamps
     * that did not match the audio, and repeated lines. It is back only where it can be measured
     * against the whole-file result on the same recording, which is what should have settled it before
     * it ever reached a phone.
     *
     * Exposed rather than duplicated: a benchmark that re-implemented the native call would be
     * measuring its own copy of the pipeline, and the two would drift apart.
     *
     * @param audio 16 kHz mono, in [-1, 1].
     * @return segments with times relative to **this buffer**, not to any larger recording.
     */
    fun transcribeBuffer(
        ptr: Long,
        audio: FloatArray,
        language: String?,
        prompt: String?,
        vadModelPath: String?,
        settings: DecodeSettings,
    ): List<TranscriptSegment> {
        whisperActive = true
        try {
            WhisperNative.transcribe(
                ptr, audio, preferredThreadCount(), language, prompt,
                vadModelPath, settings.beamSize, settings.maxTextCtx,
            )
        } finally {
            whisperActive = false
        }
        return readSegments(ptr, vadOn = vadModelPath != null)
    }

    /**
     * The run that just finished, as segments in the timeline of the audio it was given.
     *
     * One reader for both callers, so the single-buffer path the harness measures and the chunked path
     * the app runs cannot drift apart in what they do to whisper's output:
     *  - [SpeechGapSnap] puts a line stamped inside a removed pause back where it was spoken (#25);
     *  - [SpeakerSeamSplit.atSeams] offers the places a line could be cut, for the runner to settle once
     *    it has the speaker turns. Guarded: a native layer that cannot give words costs the cut, never
     *    the transcript.
     */
    private fun readSegments(ptr: Long, vadOn: Boolean): List<TranscriptSegment> {
        val speech = speechStretches(ptr, vadOn)
        val indexed = (0 until WhisperNative.segmentCount(ptr)).map { i ->
            i to TranscriptSegment(
                startMs = WhisperNative.segmentStartMs(ptr, i),
                endMs = WhisperNative.segmentEndMs(ptr, i),
                text = WhisperNative.segmentText(ptr, i).trim(),
            )
        }.filter { (_, segment) -> segment.text.isNotEmpty() }

        val snapped = SpeechGapSnap.apply(indexed.map { it.second }, speech)
        return snapped.mapIndexed { position, segment ->
            val words = runCatching {
                SpeakerSeamSplit.parseWords(WhisperNative.segmentWords(ptr, indexed[position].first))
            }.getOrDefault(emptyList())
            SpeakerSeamSplit.atSeams(segment, words, speech)
        }
    }

    /**
     * The stretches of speech the VAD kept, in original audio time, or empty when it was not used.
     *
     * Feeds [SpeechGapSnap], which puts a line stamped inside a removed pause back where it was
     * actually spoken — issue #25.
     */
    private fun speechStretches(ptr: Long, vadOn: Boolean): List<SpeechGapSnap.Speech> {
        if (!vadOn) return emptyList()
        return runCatching {
            (0 until WhisperNative.vadSegmentCount(ptr)).map { i ->
                SpeechGapSnap.Speech(
                    startMs = WhisperNative.vadSegmentStartMs(ptr, i),
                    endMs = WhisperNative.vadSegmentEndMs(ptr, i),
                )
            }
        }.onFailure { AppLogger.w(TAG, "Could not read the VAD stretches: ${it.message}") }
            .getOrDefault(emptyList())
    }

    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelPath: String,
        language: String?,
        /** Words to expect — see [TranscriptionPrompt]. Null for none. */
        prompt: String? = null,
        /**
         * How to decode. Overridden only by the instrumented benchmark, which measures variants of
         * it against each other — see [DecodeSettings] for what each field costs and buys.
         */
        settings: DecodeSettings = DecodeSettings.DEFAULT,
        /**
         * Offered every decoded chunk before the mono downmix, so who-spoke can be worked out from the
         * decode this call is already doing (issue #38). Null for none — which is what a caller that
         * does not want speaker labels passes, and what every test passes.
         */
        speakers: OfflineSpeakerLabeller? = null,
    ): List<TranscriptSegment> = withContext(dispatcher) {
        abortRequested.set(false)
        // Set before the decode, not after: decoding the audio is itself minutes of CPU on a long
        // call, and that is exactly when other background work must stand aside.
        isRunning = true
        try {
        // Cut the call into passes so peak memory stops depending on its length: one slice is decoded
        // and held at a time. A call short enough to fit in a single pass produces exactly one chunk
        // covering the whole file, which is byte-for-byte the behaviour that existed before — the
        // common case pays none of this.
        val durationMs = runCatching { AudioDecoder.durationMs(context, uri) }.getOrDefault(0L)
        val plan = ChunkPlan.plan(durationMs)
        chunkCount = plan.size
        chunkIndex = 0
        if (plan.size > 1) {
            AppLogger.i(TAG, "Transcribing ${durationMs / 1000}s in ${plan.size} passes of up to ${ChunkPlan.TARGET_CHUNK_MS / 1000}s")
        }

        // Setup starts here: everything from this point to the first chunk is a cost the run pays
        // once, whatever the length of the call.
        lastSetupMs = 0L
        val setupStartedAt = SystemClock.elapsedRealtime()
        val ptr = WhisperNative.initContext(modelPath, context.applicationInfo.nativeLibraryDir)
        if (ptr == 0L) error("Could not load whisper model at $modelPath")
        try {
            val threads = preferredThreadCount()
            lastThreadCount = threads
            // Extracted here rather than at startup: it costs a file copy once in the app's
            // lifetime, and this is the only place that needs it. Null when it could not be
            // unpacked, which decodes exactly as the app did before VAD existed.
            val vadModelPath = if (settings.useVad) VadModel.ensureExtracted(context) else null
            // Setup ends here — the next thing this does is decode audio. Captured before the loop
            // so a run aborted part-way still leaves an honest figure behind.
            lastSetupMs = SystemClock.elapsedRealtime() - setupStartedAt
            AppLogger.i(
                TAG,
                "Transcribing with $threads threads, lang=${language ?: "auto"}, $settings " +
                    "(setup took ${lastSetupMs} ms)",
            )

            val all = mutableListOf<TranscriptSegment>()
            plan.forEachIndexed { index, chunk ->
                chunkIndex = index
                if (abortRequested.get()) return@forEachIndexed

                // Decoded here, inside the loop, and released at the end of it. This is the whole
                // point: only one slice is ever on the heap.
                // A single-pass plan asks for the WHOLE file explicitly, rather than for a range that
                // happens to cover it. The difference is not cosmetic: a range decode cannot check the
                // decoded length against the container's declared duration — a chunk is *meant* to be
                // shorter — so naming a range here would quietly disable the guard that exists because
                // a 45-second clip once transcribed for over eleven minutes.
                val singlePass = plan.size == 1
                val slice = AudioDecoder.decodeRange(
                    context, uri,
                    fromMs = if (singlePass) 0L else chunk.decodeFromMs,
                    toMs = if (singlePass || chunk.endMs <= 0L) Long.MAX_VALUE else chunk.endMs,
                    // Wrapped: speaker labels are a bonus on top of a transcript, and nothing about
                    // them is worth losing the transcript for.
                    onInterleaved = speakers?.let { labeller ->
                        { pcm, length, channels, rate, startMs ->
                            runCatching { labeller.accept(pcm, length, channels, rate, startMs) }
                                .onFailure { AppLogger.w(TAG, "speaker labelling skipped this chunk: ${it.message}") }
                            Unit
                        }
                    },
                ) { abortRequested.get() }

                // The number that would have caught the first failure without a benchmark: where the
                // slice was asked to begin, and where it says it really began. A chunk whose audio
                // does not start where its timestamps claim shifts every line inside it, which reads
                // exactly like "the transcript does not match the voice". Logged per pass so a bad
                // run can be diagnosed from a debug report instead of from a description.
                if (!singlePass) {
                    AppLogger.i(
                        TAG,
                        "Pass ${index + 1}/${plan.size}: asked ${chunk.decodeFromMs} ms, decoded from " +
                            "${slice.startMs} ms (drift ${slice.startMs - chunk.decodeFromMs} ms), " +
                            "${slice.audio.size / AudioDecoder.TARGET_SAMPLE_RATE}s of audio",
                    )
                }

                if (slice.audio.isEmpty()) {
                    AppLogger.w(TAG, "Pass ${index + 1}/${plan.size} decoded no audio")
                    return@forEachIndexed
                }

                // Bracketed as tightly as possible around the native call: outside it, the counter
                // still holds whatever the previous run left behind.
                whisperActive = true
                try {
                    WhisperNative.transcribe(
                        ptr, slice.audio, threads, language, prompt,
                        vadModelPath, settings.beamSize, settings.maxTextCtx,
                    )
                } finally {
                    whisperActive = false
                }

                // Logged because a missing VAD model is not an error to whisper.cpp — it decodes
                // everything instead — so zero here on a run that asked for VAD is the only signal
                // that the trimming did not happen.
                if (vadModelPath != null) {
                    AppLogger.i(TAG, "VAD kept ${WhisperNative.vadSegmentCount(ptr)} speech stretches")
                }

                // Before the chunk offset is added, so the stretches and the segments are in the same
                // (chunk-local) timeline — issue #25.
                val raw = readSegments(ptr, vadOn = vadModelPath != null)

                // Stitched against where the audio REALLY started, which a seek may have moved earlier
                // than the plan asked for. Using the planned offset instead would skew every timestamp
                // in the chunk by up to one sync interval.
                all += ChunkPlan.stitch(raw, chunk.copy(decodeFromMs = slice.startMs))
            }

            all.also { AppLogger.i(TAG, "Produced ${it.size} segments across ${plan.size} pass(es)") }
        } finally {
            WhisperNative.freeContext(ptr)
        }
        } finally {
            isRunning = false
            chunkCount = 0
            chunkIndex = 0
        }
    }
}
