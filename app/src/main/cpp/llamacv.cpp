/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

// JNI bridge to llama.cpp, for summarising a transcript on the device.
//
// Deliberately the same shape as whispercv.cpp next door: an opaque pointer paired with exactly one
// free, one Kotlin owner serialising access, and a real abort flag — because a generate is one
// blocking native call that neither coroutine cancellation nor WorkManager can interrupt. That
// lesson was learned the expensive way on the transcription side; it is not relearned here.
//
// Summary text is never logged. It is the substance of a private call, exactly like the transcript
// it is made from.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml.h"
#include "jni_utf8.h"

#define TAG "CV:llamacv"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// Set from any thread; read by the token loop and by ggml between computation steps.
static std::atomic<bool> g_abort{false};

static bool abort_requested(void * /* user_data */) {
    return g_abort.load(std::memory_order_relaxed);
}

// The CPU backend ships as its own .so, so it has to be dlopen'd before a model will load. Once per
// process, and not once per generate.
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
        llama_log_set([](ggml_log_level level, const char *text, void *) {
            if (level < GGML_LOG_LEVEL_WARN || text == nullptr) return;
            // Upstream chatter only -- prompts and generated text do not pass through here today.
            //
            // Truncated anyway, because "today" is the whole problem: this is an unbounded string
            // from a dependency that moves under us on every submodule bump, and it lands in the
            // debug report a user attaches to a public issue. The report redacts names and numbers,
            // but it cannot redact a fragment of somebody's conversation. A warning that needs more
            // than this to be useful is not a warning, so the cap costs nothing and means an
            // upstream change cannot quietly turn a diagnostic into a leak.
            static constexpr size_t MAX_UPSTREAM_LOG_CHARS = 300;
            const size_t len = strnlen(text, MAX_UPSTREAM_LOG_CHARS + 1);
            if (len > MAX_UPSTREAM_LOG_CHARS) {
                LOGW("%.*s... [truncated]", static_cast<int>(MAX_UPSTREAM_LOG_CHARS), text);
            } else {
                LOGW("%.*s", static_cast<int>(len), text);
            }
        }, nullptr);
    });
}

// Reads the backend registry, so it needs the backends loaded first -- with none registered it
// returns the empty string rather than failing, which is a worse way to find out.
extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_summary_LlamaNative_systemInfo(JNIEnv *env, jobject /* this */, jstring lib_dir) {
    const char *c_dir = env->GetStringUTFChars(lib_dir, nullptr);
    ensure_backends(c_dir);
    env->ReleaseStringUTFChars(lib_dir, c_dir);
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT void JNICALL
Java_com_baba_callvault_summary_LlamaNative_requestAbort(JNIEnv * /* env */, jobject /* this */) {
    g_abort.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_baba_callvault_summary_LlamaNative_initContext(JNIEnv *env, jobject /* this */, jstring path,
                                                        jstring lib_dir) {
    const char *c_dir = env->GetStringUTFChars(lib_dir, nullptr);
    ensure_backends(c_dir);
    env->ReleaseStringUTFChars(lib_dir, c_dir);

    const char *c_path = env->GetStringUTFChars(path, nullptr);
    if (c_path == nullptr) return 0;
    llama_model_params params = llama_model_default_params();
    // CPU only. A phone's GPU/NPU is not reliably reachable from an app process, and the point of
    // this spike is what an ordinary device can do without one.
    params.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(c_path, params);
    if (model == nullptr) {
        LOGW("could not load the model");
    }
    env->ReleaseStringUTFChars(path, c_path);
    return reinterpret_cast<jlong>(model);
}

extern "C" JNIEXPORT void JNICALL
Java_com_baba_callvault_summary_LlamaNative_freeContext(JNIEnv * /* env */, jobject /* this */, jlong ptr) {
    if (ptr == 0) return;
    llama_model_free(reinterpret_cast<llama_model *>(ptr));
}

/** Tokens in [text], or a negative value if it cannot be tokenised. Used to size chunks honestly. */
extern "C" JNIEXPORT jint JNICALL
Java_com_baba_callvault_summary_LlamaNative_countTokens(JNIEnv *env, jobject /* this */, jlong ptr, jstring text) {
    if (ptr == 0) return -1;
    const auto *model = reinterpret_cast<const llama_model *>(ptr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *c_text = env->GetStringUTFChars(text, nullptr);
    if (c_text == nullptr) return -1;   // out of memory: a pending exception, nothing to release
    const int n = -llama_tokenize(vocab, c_text, (int32_t) strlen(c_text), nullptr, 0, true, true);
    env->ReleaseStringUTFChars(text, c_text);
    return n;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_baba_callvault_summary_LlamaNative_grammarAccepted(JNIEnv *env, jobject /* this */, jlong ptr,
                                                            jstring grammar) {
    // Whether llama will actually apply this grammar, asked before it matters.
    //
    // generate() falls back to unconstrained output when a grammar fails to parse, and that fallback
    // is the right one — a summary needing repair beats no summary. What was wrong is that it was
    // INVISIBLE: a LOGW to logcat, which rotates within minutes and is off by default, while the app
    // produced plausible-looking output with no constraint behind it. The grammar is generated by our
    // own code, so a parse failure is a programming error that could ship unnoticed.
    //
    // Exposed separately rather than folded into generate() so the answer can be logged through the
    // app's own logger, where a bug report can actually find it.
    if (ptr == 0 || grammar == nullptr) return JNI_FALSE;

    const auto *model = reinterpret_cast<const llama_model *>(ptr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *c_grammar = env->GetStringUTFChars(grammar, nullptr);
    bool ok = false;
    if (c_grammar != nullptr && *c_grammar != '\0') {
        llama_sampler *g = llama_sampler_init_grammar(vocab, c_grammar, "root");
        ok = g != nullptr;
        if (g != nullptr) llama_sampler_free(g);
    }
    env->ReleaseStringUTFChars(grammar, c_grammar);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_summary_LlamaNative_generate(
        JNIEnv *env, jobject /* this */, jlong ptr, jstring prompt, jint maxTokens, jint threads,
        jstring grammar) {

    if (ptr == 0) return env->NewStringUTF("");

    // Cleared here, not on abort: a stale flag left by a previous run must never kill this one.
    g_abort.store(false, std::memory_order_relaxed);

    auto *model = reinterpret_cast<llama_model *>(ptr);
    const llama_vocab *vocab = llama_model_get_vocab(model);

    const char *c_prompt = env->GetStringUTFChars(prompt, nullptr);
    if (c_prompt == nullptr) return env->NewStringUTF("");

    // Wrap the prompt in the model's own chat template.
    //
    // These are instruct models: they were trained to answer text arriving inside their template,
    // with the assistant turn opened for them. Handed a bare paragraph they still produce something,
    // but it is not what they are good at — and measuring that would be measuring our mistake rather
    // than the model. Falls back to the bare prompt for a model that carries no template.
    std::string templated;
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) {
        llama_chat_message msg{"user", c_prompt};
        // Twice the input is upstream's recommended allocation; the template adds only control tags.
        std::vector<char> buf(strlen(c_prompt) * 2 + 1024);
        const int32_t written =
                llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        if (written > 0 && written <= (int32_t) buf.size()) {
            templated.assign(buf.data(), written);
        }
    }
    if (templated.empty()) templated = c_prompt;

    const char *text = templated.c_str();
    const auto prompt_len = (int32_t) templated.size();

    const int n_prompt = -llama_tokenize(vocab, text, prompt_len, nullptr, 0, true, true);
    std::vector<llama_token> tokens(n_prompt);
    const bool tokenised =
            llama_tokenize(vocab, text, prompt_len, tokens.data(), (int32_t) tokens.size(), true, true) >= 0;
    env->ReleaseStringUTFChars(prompt, c_prompt);

    if (!tokenised) {
        LOGW("could not tokenise the prompt");
        return env->NewStringUTF("");
    }

    llama_context_params ctx_params = llama_context_default_params();
    // Room for the prompt and the answer, but never past what the model was trained for: asking for
    // more silently degrades output rather than failing, which is the worst way to be wrong.
    const uint32_t wanted = (uint32_t) (n_prompt + maxTokens);
    ctx_params.n_ctx           = std::min(wanted, (uint32_t) llama_model_n_ctx_train(model));
    ctx_params.n_batch         = (uint32_t) n_prompt > 0 ? (uint32_t) n_prompt : 512;
    ctx_params.n_threads       = threads;
    ctx_params.n_threads_batch = threads;
    // Aborts the prefill too, not just the token loop. On a long transcript the prefill IS the wait,
    // so an abort that only checked between tokens would appear to do nothing for a minute.
    ctx_params.abort_callback      = abort_requested;
    ctx_params.abort_callback_data = nullptr;

    // Keep the attention cache at 8 bits instead of 16.
    //
    // The KV cache holds what the model has read so far, and its size grows with the context — on a
    // long transcript it is a large fraction of what the summary run costs. Q8_0 halves it for a few
    // percent of throughput, which is a good trade on a phone that is also holding a ~2.6 GB model.
    //
    // llama enables flash attention by itself when V is quantised (flash_attn_type defaults to AUTO),
    // so this needs no separate switch. What it does need is the fallback below: quantised K and V
    // both require the head dimension to divide by the block size, and a model where it does not
    // makes llama_init_from_model return null — which here would mean every summary silently produces
    // nothing at all. A memory optimisation must never be able to disable the feature.
    ctx_params.type_k = GGML_TYPE_Q8_0;
    ctx_params.type_v = GGML_TYPE_Q8_0;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGW("context creation failed with a quantised KV cache; retrying at full precision");
        ctx_params.type_k = GGML_TYPE_F16;
        ctx_params.type_v = GGML_TYPE_F16;
        ctx = llama_init_from_model(model, ctx_params);
    }
    if (ctx == nullptr) {
        LOGW("could not create a context");
        return env->NewStringUTF("");
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());

    // A grammar, when one is given, BEFORE the greedy pick.
    //
    // Measured: asked for strict JSON, Gemma returned a summary string with no closing quote, and
    // the whole object failed to parse. No wording fixes that — a small model under a token cap
    // drops a quote, and "please emit valid JSON" is a request. A grammar is not a request: tokens
    // that would break the structure are removed from consideration before the pick, so malformed
    // output becomes unsampleable rather than unlikely.
    //
    // Ordering matters. The grammar must filter the candidates the greedy sampler then chooses
    // from; after it, the choice is already made and the constraint would do nothing.
    if (grammar != nullptr) {
        const char *c_grammar = env->GetStringUTFChars(grammar, nullptr);
        if (c_grammar != nullptr && *c_grammar != '\0') {
            llama_sampler *g = llama_sampler_init_grammar(vocab, c_grammar, "root");
            // Null means the grammar itself failed to parse. Carrying on unconstrained is the right
            // failure: a summary that needs repairing beats no summary, and the log says why.
            if (g != nullptr) llama_sampler_chain_add(sampler, g);
            else LOGW("grammar failed to parse; generating unconstrained");
        }
        env->ReleaseStringUTFChars(grammar, c_grammar);
    }

    // Greedy. A summary is not creative writing, and a deterministic run is one a measurement can
    // actually compare across models.
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string out;
    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());

    // Declared out here, not inside the loop. llama_batch_get_one keeps a POINTER to the token, and
    // the batch is read at the top of the next iteration — so a token scoped to the loop body would
    // be read after it had gone. The upstream example does the same, for the same reason.
    llama_token sampled = 0;

    const int64_t t_start = ggml_time_us();
    int generated = 0;

    for (int n_pos = 0; n_pos + batch.n_tokens < (int) ctx_params.n_ctx && generated < maxTokens; ) {
        if (llama_decode(ctx, batch) != 0) break;   // includes the abort path
        if (g_abort.load(std::memory_order_relaxed)) break;

        n_pos += batch.n_tokens;

        sampled = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, sampled)) break;

        char piece[256];
        const int n = llama_token_to_piece(vocab, sampled, piece, sizeof(piece), 0, true);
        if (n < 0) break;
        out.append(piece, n);
        generated++;

        batch = llama_batch_get_one(&sampled, 1);
    }

    // Counts and timings only — never the text.
    LOGI("generated %d tokens from a %d-token prompt in %lld ms",
         generated, n_prompt, (long long) ((ggml_time_us() - t_start) / 1000));

    llama_sampler_free(sampler);
    llama_free(ctx);

    // Never NewStringUTF the raw bytes: the token loop can stop mid-character, and an invalid
    // sequence aborts the process (C47).
    return env->NewStringUTF(to_modified_utf8(out.c_str()).c_str());
}
