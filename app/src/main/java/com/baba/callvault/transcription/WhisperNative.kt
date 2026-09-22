/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * JNI bridge to whisper.cpp.
 *
 * whisper.cpp forbids touching one context from more than one thread at a time, so nothing may call
 * these functions directly — [TranscriptionEngine] owns that serialisation and is the only supported
 * entry point. The raw `Long` context pointer is likewise not something callers should hold: it must
 * be paired with exactly one [freeContext], which the engine guarantees with `try`/`finally`.
 *
 * This runs in the app process. It must never be used from the privileged recorder daemon:
 * transcription needs no privilege, and sharing a process with the capture path would put the most
 * fragile part of the app behind a CPU-saturating job.
 *
 * [libDir] on the two entry points below is `context.applicationInfo.nativeLibraryDir`. ggml's CPU
 * kernels ship as one .so per ARM feature set and are dlopen'd by directory, and ggml's own default
 * search path — the executable's directory and the current one, i.e. /system/bin and / — holds none
 * of ours. Getting it wrong is silent: the backends are simply not found, and every model then
 * fails to load for no stated reason.
 */
object WhisperNative {
    init { System.loadLibrary("whispercv") }

    /**
     * ggml build/CPU feature string. Used to confirm the native library loaded at all.
     *
     * Also the answer to "which CPU variant did this phone pick": the flags it lists are the loaded
     * backend's own, so DOTPROD or MATMUL_INT8 reading 1 is proof the fast kernels are in use.
     */
    external fun systemInfo(libDir: String): String

    /** @return an opaque context pointer, or 0 when the model could not be loaded. */
    external fun initContext(modelPath: String, libDir: String): Long

    /** Releases a context from [initContext]. Safe to call with 0. */
    external fun freeContext(ptr: Long)

    /**
     * Runs recognition over [audio] — 16 kHz mono float in [-1, 1]. Results are read afterwards with
     * [segmentCount] and the segment accessors.
     *
     * @param language ISO code such as "he", or null to auto-detect. Passing the wrong language is
     *   not a soft failure: decoding Hebrew as English yields confident nonsense.
     * @param prompt   words to expect — names, places, jargon — biasing how they are spelled.
     *   Null or empty for none. A bias, never a rule, and a long one makes whisper repeat it back.
     * @param vadModelPath path to the Silero model from [VadModel], or null to decode every sample.
     *   Present, the non-speech is trimmed and what remains is concatenated — it does **not**
     *   re-segment, and must never be turned into something that does.
     * @param beamSize beam width, or 1 for greedy. See [DecodeSettings.beamSize].
     * @param maxTextCtx cap on rolling text conditioning, 0 to disable, or -1 to leave whisper's own
     *   default alone. See [DecodeSettings.maxTextCtx].
     */
    external fun transcribe(
        ptr: Long,
        audio: FloatArray,
        threads: Int,
        language: String?,
        prompt: String?,
        vadModelPath: String?,
        beamSize: Int,
        maxTextCtx: Int
    )

    /**
     * How many stretches of speech the VAD kept in the run that just finished; 0 when it was off.
     *
     * The only way to tell VAD working from VAD silently absent. whisper.cpp does not fail a run
     * whose VAD model is missing or unreadable — it processes everything instead — so a transcript
     * produced with no VAD at all is indistinguishable from one produced with it, except for this.
     */
    external fun vadSegmentCount(ptr: Long): Int

    /**
     * Where the i-th stretch of speech the VAD kept begins and ends, in ORIGINAL audio time.
     *
     * Issue #25: whisper's own segment timestamps are mapped back through a table that represents every
     * removed pause with a hardcoded 100 ms, so a line after a long pause is stamped far too early.
     * These are the real boundaries, and [SpeechGapSnap] uses them to put such a line back.
     */
    external fun vadSegmentStartMs(ptr: Long, index: Int): Long

    /** @see vadSegmentStartMs */
    external fun vadSegmentEndMs(ptr: Long, index: Int): Long

    /**
     * Asks a run in progress to stop, from any thread.
     *
     * [transcribe] is one blocking call that neither coroutine cancellation nor WorkManager can
     * interrupt, so without this Stop stopped nothing: the phone stayed at ~600% CPU until the run
     * finished by itself. whisper checks before each computation step, so this lands in well under a
     * second. The flag is cleared at the start of every [transcribe], so a stale abort cannot kill
     * the following run.
     */
    external fun requestAbort()

    /**
     * How far through the current recording whisper is, 0-100.
     *
     * Polled, not pushed. A callback would arrive on whisper's own thread and have to attach itself
     * to the JVM to say anything; the UI already runs a tick, and the only value it ever wants is
     * the latest one.
     *
     * Exists because a run over a long call showed nothing but a spinning circle for minutes, which
     * is indistinguishable from a hang — the user cannot tell whether to wait or to force-stop.
     */
    external fun progressPercent(): Int

    external fun segmentCount(ptr: Long): Int

    /** Milliseconds from the start of the recording (whisper's centiseconds are converted natively). */
    external fun segmentStartMs(ptr: Long, index: Int): Long

    external fun segmentEndMs(ptr: Long, index: Int): Long

    external fun segmentText(ptr: Long, index: Int): String

    /**
     * The i-th segment's words, one per line as `<start in ms>\t<word>`, on the VAD's compressed
     * timeline. Parsed by [SpeakerSeamSplit.parseWords]; see the native side for why words are assembled
     * there rather than here.
     */
    external fun segmentWords(ptr: Long, index: Int): String
}
