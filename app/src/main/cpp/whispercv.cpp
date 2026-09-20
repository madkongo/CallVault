// JNI bridge to whisper.cpp for on-device call transcription.
//
// Deliberately ours rather than upstream's examples/whisper.android JNI, which hardcodes
// `params.language = "en"` and returns a single pre-formatted string. Both are disqualifying here:
// the calls this app records are frequently not English (decoding Hebrew as English produces
// garbage), and transcript storage needs per-segment start/end times rather than one blob.
//
// Runs in the app process, never in the privileged shell-uid recorder daemon — transcription needs
// no privilege and must not share a process with the capture path.
//
// Thread-safety: whisper.cpp forbids touching one context from more than one thread at a time.
// Nothing is locked here; TranscriptionEngine serialises every call onto a single thread instead.

#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include "whisper.h"
#include "ggml-backend.h"

static inline whisper_context *ctx_of(jlong p) {
    return reinterpret_cast<whisper_context *>(p);
}

// The CPU backend ships as its own .so, so it has to be dlopen'd before a model will load. Once per
// process, and not once per transcription. The same code, and the same reasoning, as llamacv.cpp --
// the two engines share one ggml but not one process-wide registry call.
//
// From an explicit directory, because ggml's own default is the executable's directory and the
// current one -- for an Android app that is /system/bin and /, and neither holds our libraries. It
// finds nothing there and says nothing about it, and the first symptom is that every model fails to
// load. [lib_dir] is the app's nativeLibraryDir, handed down from Kotlin.
//
// The directory holds one libggml-cpu-android_armv*.so per ARM feature set (see the CPU-variant
// block in CMakeLists.txt); ggml scores each against HWCAP and keeps the best the phone can run.
static std::once_flag g_backends_once;

static void ensure_backends(const char *lib_dir) {
    // Copied, not captured by reference: call_once runs the lambda after this function's argument
    // is gone in every caller that loses the race.
    const std::string dir = lib_dir ? lib_dir : "";
    std::call_once(g_backends_once, [dir] {
        ggml_backend_load_all_from_path(dir.empty() ? nullptr : dir.c_str());
    });
}

// Set from any thread to abort a run in progress.
//
// Without this, Stop cannot stop anything: whisper_full is a single blocking call that neither
// coroutine cancellation nor WorkManager can interrupt, so tapping Stop left the phone at ~600% CPU
// until the run finished on its own — minutes on a short call, hours on a long one — with the row
// still showing a spinner the whole time. whisper calls abort_callback before each ggml computation,
// so returning true here ends the run in a fraction of a second.
static std::atomic<bool> g_abort{false};

/**
 * How far through the current recording whisper is, 0-100.
 *
 * Polled rather than pushed: a callback into Kotlin would arrive on whisper's own thread and need
 * AttachCurrentThread, and the UI reads this on a tick it already runs. An int is atomic and the
 * reader only ever wants the latest value, so there is nothing to synchronise.
 */
static std::atomic<int> g_progress{0};

/** Total audio length of the run in progress, so a segment's end time can be turned into a share. */
static std::atomic<int64_t> g_total_ms{0};

/** Monotonic: progress that retreats reads as a fault even when the newer figure is the better one. */
static void raise_progress(int percent) {
    if (percent < 0) percent = 0;
    if (percent > 99) percent = 99;   // 100 belongs to the caller, when the run has really finished
    int seen = g_progress.load(std::memory_order_relaxed);
    while (percent > seen &&
           !g_progress.compare_exchange_weak(seen, percent, std::memory_order_relaxed)) {
    }
}

static void progress_reported(struct whisper_context *, struct whisper_state *, int progress, void *) {
    // Coarse: one step per thirty seconds of audio. Kept because it is the only thing that moves
    // through a silence, where no segments are produced at all.
    raise_progress(progress);
}

/**
 * Fine-grained progress, from where in the call each transcribed phrase ended.
 *
 * The progress callback above fires once per thirty-second chunk, so a short call produces about
 * three updates in total and the figure appears stuck between them. Segments arrive several times
 * per chunk and carry a real timestamp, so this is genuinely continuous rather than a prediction —
 * it is the same number, measured more often.
 */
static void segment_reported(struct whisper_context *, struct whisper_state *state, int, void *) {
    const int64_t total = g_total_ms.load(std::memory_order_relaxed);
    if (total <= 0) return;

    const int n = whisper_full_n_segments_from_state(state);
    if (n <= 0) return;

    // Centiseconds upstream; milliseconds here, as everywhere else in this file.
    const int64_t end_ms = whisper_full_get_segment_t1_from_state(state, n - 1) * 10;
    raise_progress(static_cast<int>((end_ms * 100) / total));
}

static bool abort_requested(void *) {
    return g_abort.load(std::memory_order_relaxed);
}


/**
 * Whisper's text, made safe to hand to JNI.
 *
 * `NewStringUTF` does not validate and does not fail politely: given a byte sequence that is not
 * well-formed **modified** UTF-8 it aborts the entire process with SIGABRT. That is not theoretical
 * — it happened on a real 26-minute Hebrew call, where whisper emitted a segment cut in the middle of
 * a two-byte character (`0xd7` followed by another `0xd7`) and the app died outright:
 *
 *     JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
 *     illegal continuation byte 0xd7 ... in call to NewStringUTF
 *
 * whisper builds a segment by concatenating tokens, and a token boundary is not a character
 * boundary, so a truncated multi-byte character is an ordinary thing for it to produce — most often
 * on non-Latin scripts, where nearly every character is multi-byte. A transcriber that crashes the
 * app on some Hebrew calls and not others is the worst possible shape for this bug.
 *
 * Two departures from plain UTF-8, both required by the *modified* form JNI expects:
 *  - a four-byte sequence (anything outside the BMP, e.g. an emoji) must be re-encoded as a
 *    surrogate pair of two three-byte sequences, not passed through;
 *  - an embedded NUL would have to become `0xC0 0x80`, which cannot arise here because the input is
 *    already a NUL-terminated C string.
 *
 * Malformed bytes are dropped rather than replaced. A replacement character in the middle of a word
 * is not more useful to a reader than the word simply being a character shorter, and it would travel
 * into the summary and the search index as content.
 */
static std::string to_modified_utf8(const char *s) {
    std::string out;
    if (s == nullptr) return out;

    const auto *p = reinterpret_cast<const unsigned char *>(s);
    while (*p) {
        const unsigned char c = *p;

        int len;
        if (c < 0x80)             len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else { ++p; continue; }              // stray continuation byte, or an invalid lead

        // Every continuation byte must be present and well-formed. This is the check that was
        // missing: a sequence truncated by the end of the string fails here instead of aborting.
        bool ok = true;
        for (int k = 1; k < len; ++k) {
            if (p[k] == 0 || (p[k] & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { ++p; continue; }

        if (len < 4) {
            out.append(reinterpret_cast<const char *>(p), len);
            p += len;
            continue;
        }

        // Outside the BMP: decode, then emit the surrogate pair as two three-byte sequences, which
        // is what modified UTF-8 requires and what plain UTF-8 would get wrong.
        const uint32_t cp = ((uint32_t) (p[0] & 0x07) << 18) | ((uint32_t) (p[1] & 0x3F) << 12) |
                            ((uint32_t) (p[2] & 0x3F) << 6)  |  (uint32_t) (p[3] & 0x3F);
        p += 4;
        if (cp < 0x10000 || cp > 0x10FFFF) continue;   // overlong or out of range
        const uint32_t v    = cp - 0x10000;
        const uint32_t hi   = 0xD800 + (v >> 10);
        const uint32_t lo   = 0xDC00 + (v & 0x3FF);
        for (uint32_t half : {hi, lo}) {
            out.push_back((char) (0xE0 | (half >> 12)));
            out.push_back((char) (0x80 | ((half >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (half & 0x3F)));
        }
    }
    return out;
}

extern "C" {

// Lists the loaded CPU backend's own features, so this is where the phone says which variant it
// chose: DOTPROD, MATMUL_INT8 and the rest read 1 only when the .so that won the scoring was built
// with them. Needs the backends loaded first, hence the directory.
JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_systemInfo(JNIEnv *env, jobject, jstring lib_dir) {
    const char *d = env->GetStringUTFChars(lib_dir, nullptr);
    ensure_backends(d);
    env->ReleaseStringUTFChars(lib_dir, d);
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_initContext(JNIEnv *env, jobject, jstring path,
                                                                jstring lib_dir) {
    const char *d = env->GetStringUTFChars(lib_dir, nullptr);
    ensure_backends(d);
    env->ReleaseStringUTFChars(lib_dir, d);

    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    whisper_context *c = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(ctx_of(ptr));
}

JNIEXPORT jint JNICALL
Java_com_baba_callvault_transcription_WhisperNative_progressPercent(JNIEnv *, jobject) {
    return g_progress.load(std::memory_order_relaxed);
}

// Deliberately takes no context pointer: the caller asking to stop is not the thread inside
// whisper_full, and only one transcription ever runs at a time (TranscriptionEngine serialises them).
JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_requestAbort(JNIEnv *, jobject) {
    g_abort.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray audio, jint threads, jstring language,
        jstring prompt, jstring vad_model_path, jint beam_size, jint max_text_ctx) {
    // Greedy below beam-2, beam search at or above it. Both strategies are reachable because the
    // right answer is not the same everywhere: the Whisper paper (arXiv:2212.04356, Appendix D)
    // measures beam-5 improving twelve of fourteen sets on `large-v2` -- CallHome 17.6 -> 16.4 WER,
    // CORAAL 16.2 -> 14.2 -- but making the two noisiest far-field sets WORSE (AMI-SDM1 36.4 ->
    // 39.9). A phone call is telephone-domain, which is the case that gains.
    //
    // whisper-cli's own default is beam 5; this file used to hardcode greedy, which was an
    // unintended divergence rather than a decision.
    const bool use_beam = beam_size >= 2;
    whisper_full_params params = whisper_full_default_params(
            use_beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (use_beam) params.beam_search.beam_size = beam_size;
    params.print_realtime   = false;  // upstream sets this true; it floods logcat on a long call
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;  // transcribe in the spoken language, never translate to English
    params.n_threads        = threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;
    // Token times cost a post-pass over the result and do not change the text. They exist for
    // segmentWords() below: a line both speakers share is cut at the pause between them, and whisper's
    // own segment times cannot say which words fall on which side of it.
    params.token_timestamps = true;

    // How much gibberish counts as gibberish.
    //
    // When a decoded window looks degenerate, whisper throws it away and retries at a higher
    // temperature. `entropy_thold` is the line between "dense but real speech" and "the model has
    // started looping", measured as the gzip compression ratio of the text — a run of repeated words
    // compresses far better than a sentence does.
    //
    // whisper.cpp's default is 2.4, inherited from OpenAI's reference implementation and never chosen
    // for our audio. Two upstream reports arrived at 2.8 independently, and telephone speech is
    // exactly the low-SNR case where the repetition loops this catches actually happen.
    //
    // Direction, checked at the source rather than assumed — the guard is
    // `result_len > 32 && sequence.entropy < entropy_thold` → discard and retry. So **raising** this
    // makes the test STRICTER: more sequences are judged degenerate and re-decoded.
    //
    // 📐 NOT MEASURED HERE, and the cost is real: stricter means more retries, so more wall-clock, and
    // in principle a dense but genuine passage could be thrown away and re-decoded at a temperature
    // that transcribes it worse. That is the risk to watch, and it is why this is one number that can
    // be put back with one number.
    params.entropy_thold    = 2.8f;

    // How much of the PREVIOUS windows' text is fed back as conditioning for the next one.
    //
    // `no_context` above does not control this, despite the name: it clears carry-over from a
    // previous *call*, once, on entry to whisper_full. Within one run `prompt_past` is rebuilt after
    // every 30-second window and re-injected, gated here and nowhere else:
    //
    //     if (params.n_max_text_ctx > 0 && ...)   // whisper.cpp, whisper_full_with_state
    //
    // Rolling conditioning is the documented mechanism behind repetition loops, and it is a genuine
    // trade rather than a free win -- it is also what keeps proper nouns, punctuation and casing
    // consistent across a window boundary. The caller decides; see DecodeSettings for which value
    // won on a real call and why.
    //
    // Note the real ceiling is NOT the 16384 default: whisper.cpp clamps with
    // `min(n_max_text_ctx, whisper_n_text_ctx(ctx)/2)`, and n_text_ctx is 448 on every large model,
    // so the effective default is 224 tokens. 16384 never applied.
    if (max_text_ctx >= 0) params.n_max_text_ctx = max_text_ctx;

    // Voice activity detection, from the bundled Silero model. Null path = off.
    //
    // MEASURED for: Whisper paper Table 7, 10.6 -> 10.2 avg WER (-3.8% rel) helping all seven
    // datasets; WhisperX (arXiv:2303.00747) TED-LIUM 10.5 -> 9.7 with 5-gram repetitions
    // 131 -> 75 (-43%); arXiv:2501.11378 measures a 40.3% non-speech hallucination rate without it.
    // It also removes audio rather than adding work (~25% in upstream's example), so it partly pays
    // for itself -- and it is what makes beam search safe here, since the same paper finds higher
    // beam sizes hallucinate MORE on non-speech, and a call is full of ringback, hold music and line
    // noise for beam search to invent words over.
    //
    // Used to trim dead air and nothing else. whisper.cpp's VAD removes the silence and concatenates
    // what is left; it does NOT re-segment, and that distinction is the whole reason this is safe.
    // Meetily measured VAD-*fragmented* input producing 17 hallucinated segments and 14 spurious
    // "thank you"s against 11 and 0 for contiguous 30-second windows, and below ~1 s of speech 81%
    // of outputs were a single memorised word. Nothing here may be changed into something that feeds
    // whisper short pieces.
    const char *vad_path =
            (vad_model_path != nullptr) ? env->GetStringUTFChars(vad_model_path, nullptr) : nullptr;
    if (vad_path != nullptr && vad_path[0] != '\0') {
        params.vad            = true;
        params.vad_model_path = vad_path;
        params.vad_params     = whisper_vad_default_params();

        // The four defaults below are tuned for clean single-speaker audio and are wrong for a
        // two-party phone call. Every override errs towards keeping audio: the worst case of
        // keeping too much is today's behaviour, and the worst case of dropping too much is deleted
        // speech that no later stage can recover.

        // 250 ms (default) deletes backchannels -- "mm", "yeah", "ok", "כן" -- which are a large
        // share of turns on a call and carry the agreement the summariser is looking for. Silero v5
        // scores in 32 ms frames, so 100 ms is still three frames of sustained voicing: long enough
        // to reject a click or a line pop, short enough to keep a one-syllable reply.
        params.vad_params.min_speech_duration_ms = 100;

        // 30 ms (default) is thin enough to clip the onset of the first word and the release of the
        // last; whisper needs the co-articulation around a word to recognise it. faster-whisper uses
        // 400 ms and is the most-deployed VAD-plus-Whisper pipeline there is, so this follows it.
        params.vad_params.speech_pad_ms = 400;

        // 100 ms (default) ends a speech run at every within-turn breath, which then gets trimmed.
        // Half a second is the pause a speaker takes mid-sentence, and cutting it splices two
        // unrelated moments together at a 30-second window boundary. Keep pauses shorter than this.
        params.vad_params.min_silence_duration_ms = 500;

        // 0.5 (default) is calibrated on full-band audio. Telephony carries almost no energy above
        // ~3.4 kHz, so Silero's speech probability sits lower on the same speech, and quiet or
        // distant talkers fall under the bar. 0.4 buys that margin back in the safe direction.
        params.vad_params.threshold = 0.4f;
    }

    // "auto" => let whisper detect the language. Anything else is an ISO code such as "he".
    //
    // `detect_language` must stay false even when auto-detecting. Despite the name it does NOT mean
    // "please detect the language" — whisper.cpp treats it as *exit after detecting*:
    //
    //     if (params.detect_language) { return 0; }   // whisper.cpp, whisper_full_with_state
    //
    // so setting it returned success with zero segments. Every transcript came back empty and the
    // app reported "No speech was recognised", which looked like a bad recording rather than a bug —
    // and auto-detect is the default, so this affected everyone. Auto-detection already happens for
    // a null/empty/"auto" language, which is why nothing else is needed here.
    const char *lang = (language != nullptr) ? env->GetStringUTFChars(language, nullptr) : nullptr;
    params.language        = (lang != nullptr) ? lang : "auto";
    params.detect_language = false;

    // Words to expect: names, places, jargon. whisper decodes with them in mind, which is how a
    // brand said aloud comes out as a name rather than as the arithmetic it sounds like — "one plus
    // nine" was transcribed as "1 + 9" on a real call.
    //
    // A bias and not a rule. It makes the spelling likelier, never certain, and a long or unrelated
    // prompt makes whisper hallucinate it back at you — so the caller keeps it short and relevant.
    const char *hint = (prompt != nullptr) ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    params.initial_prompt = (hint != nullptr && hint[0] != '\0') ? hint : nullptr;

    // Cleared here rather than by the caller, so an abort left over from a previous run can never
    // kill the next one before it starts.
    g_abort.store(false, std::memory_order_relaxed);
    params.abort_callback           = abort_requested;
    params.abort_callback_user_data = nullptr;

    // Zeroed here, not on completion: a run that ended by abort or error must not leave the last
    // percentage behind for the next one to start from.
    g_progress.store(0, std::memory_order_relaxed);
    params.progress_callback              = progress_reported;
    params.progress_callback_user_data    = nullptr;
    params.new_segment_callback           = segment_reported;
    params.new_segment_callback_user_data = nullptr;

    const jsize n = env->GetArrayLength(audio);
    // Set before the run so the segment callback can turn an end timestamp into a share of the whole.
    g_total_ms.store((static_cast<int64_t>(n) * 1000) / WHISPER_SAMPLE_RATE, std::memory_order_relaxed);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const int rc = whisper_full(ctx_of(ptr), params, samples, n);

    // Finished is a fact, not a prediction, and it is the one number the running figure can never
    // reach on its own: the segment timestamps stop at the last words spoken, and any trailing
    // silence is progress nothing reports. Without this the figure vanished around seventy, which
    // reads as giving up rather than finishing.
    if (rc == 0 && !g_abort.load(std::memory_order_relaxed)) {
        g_progress.store(100, std::memory_order_relaxed);
    }
    // JNI_ABORT: whisper never writes to the input, so there is nothing to copy back.
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
    if (hint != nullptr) env->ReleaseStringUTFChars(prompt, hint);
    // Released only after whisper_full returns: params.vad_model_path is a borrowed pointer, and
    // whisper reads it during the run rather than copying it at assignment.
    if (vad_path != nullptr) env->ReleaseStringUTFChars(vad_model_path, vad_path);
}

// How many stretches of speech the VAD kept, for the run that just finished. 0 when VAD was off.
//
// Exists to make the VAD falsifiable from Kotlin. A missing or unreadable VAD model does not fail
// the run -- whisper.cpp falls back to processing everything -- so without this the difference
// between "VAD trimmed the dead air" and "VAD silently did nothing" is invisible, and both look
// like a normal transcript.
JNIEXPORT jint JNICALL
Java_com_baba_callvault_transcription_WhisperNative_vadSegmentCount(JNIEnv *, jobject, jlong ptr) {
    return whisper_full_n_vad_segments(ctx_of(ptr));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_vadSegmentStartMs(JNIEnv *, jobject, jlong ptr, jint i) {
    // ORIGINAL-timeline start of the i-th stretch of speech the VAD kept. Issue #25 needs these to put
    // a segment back where it was actually spoken; see SpeechGapSnap.
    return whisper_full_get_vad_segment_t0(ctx_of(ptr), i) * 10;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_vadSegmentEndMs(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_vad_segment_t1(ctx_of(ptr), i) * 10;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentCount(JNIEnv *, jobject, jlong ptr) {
    return whisper_full_n_segments(ctx_of(ptr));
}

// whisper reports segment times in CENTISECONDS; every consumer in this app works in milliseconds.
JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentStartMs(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t0(ctx_of(ptr), i) * 10;
}

JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentEndMs(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t1(ctx_of(ptr), i) * 10;
}

// The i-th segment's words, one per line as "<start in ms>\t<word>\n".
//
// Times are on the VAD's COMPRESSED timeline when VAD is on -- the audio whisper actually decoded, with
// the silence taken out -- which is exactly what SpeakerSeamSplit wants: it places each word in a kept
// stretch by that time. Measured with whisper-cli -ojf on a real call: 0.01 -> 4.74 s for one speaker,
// 5.07 -> 8.53 s for the other, the seam between the two kept stretches exactly in the gap.
//
// Words are assembled HERE, not in Kotlin, because a token is a run of bytes and not a run of characters:
// a Hebrew word is several tokens and a character can straddle two of them. A token starting with a space
// starts a word; everything else continues the one before. Special tokens (>= eot) carry no text.
extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentWords(JNIEnv *env, jobject, jlong ptr, jint i) {
    whisper_context *ctx = ctx_of(ptr);
    const whisper_token eot = whisper_token_eot(ctx);
    const int n = whisper_full_n_tokens(ctx, i);

    std::string out;
    std::string word;
    int64_t word_t0 = 0;
    auto flush = [&]() {
        if (!word.empty()) out += std::to_string(word_t0 * 10) + "\t" + word + "\n";
        word.clear();
    };
    for (int j = 0; j < n; ++j) {
        if (whisper_full_get_token_id(ctx, i, j) >= eot) continue;
        const char *text = whisper_full_get_token_text(ctx, i, j);
        if (text == nullptr || text[0] == '\0') continue;
        if (text[0] == ' ') flush();
        if (word.empty()) word_t0 = whisper_full_get_token_data(ctx, i, j).t0;
        for (const char *c = text; *c != '\0'; ++c) {
            // The separators of this format must not appear inside a word.
            if (*c != '\t' && *c != '\n' && !(word.empty() && *c == ' ')) word += *c;
        }
    }
    flush();
    return env->NewStringUTF(to_modified_utf8(out.c_str()).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentText(JNIEnv *env, jobject, jlong ptr, jint i) {
    // Never NewStringUTF whisper's bytes directly — see to_modified_utf8. It aborts the process.
    return env->NewStringUTF(to_modified_utf8(whisper_full_get_segment_text(ctx_of(ptr), i)).c_str());
}

}
