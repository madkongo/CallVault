/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.transcription.NativeBackendGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one place a llama.cpp model is loaded, used and freed.
 *
 * Everything here exists because the native side has two rules that are easy to break from Kotlin:
 * one model may not be driven from two threads at once, and every load must be paired with exactly
 * one free. A [Mutex] enforces the first; `try`/`finally` the second.
 *
 * Neither prompts nor generated text are ever logged — they are the substance of a private call.
 */
object SummaryEngine {

    private const val TAG = "CV:SummaryEngine"

    /**
     * The same thread policy transcription uses, and for the same measured reason.
     *
     * Both drive ggml, which synchronises its threads at every layer, so a thread on one of the
     * phone's efficiency cores does not add its share — it makes the others wait for it at each
     * barrier. Sharing the policy also means one place to change when it is measured again, rather
     * than two that quietly disagree.
     */
    private val threads: Int get() = TranscriptionEngine.preferredThreadCount()

    private val mutex = Mutex()

    /**
     * Whether a model is loaded and working right now.
     *
     * Mirrors [TranscriptionEngine.isRunning] and exists for a sharper reason. This object
     * serialises on [mutex], so a second run does not fail — it waits, in silence, behind a button
     * the user has already pressed and which appears to have done nothing. Asking here lets the
     * caller refuse up front and say why, rather than queueing invisibly.
     */
    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Whether the last run was stopped rather than finishing.
     *
     * The authority on *how* a run ended. An aborted generate returns normally with a short answer,
     * so without this a stopped run is indistinguishable from a very terse summary — the same trap
     * that made a stopped transcription look like a failed one.
     */
    private val aborted = AtomicBoolean(false)

    /**
     * Loads [modelPath], runs [block] against it, and frees it — whatever happens.
     *
     * The model stays loaded only for the duration of [block] rather than being cached. A 4B model
     * at Q4 is well over a gigabyte of resident memory, and holding that for a screen the user may
     * never open again is not a trade worth making on a phone.
     *
     * [context] is needed only to find the app's own library directory, which is where ggml's CPU
     * backends have to be dlopen'd from — see [LlamaNative]. It leads the parameter list to match
     * [TranscriptionEngine.transcribe], which takes one for its own reasons.
     */
    suspend fun <T> withModel(
        context: Context,
        modelPath: String,
        block: suspend (Session) -> T
    ): T =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val libDir = context.applicationInfo.nativeLibraryDir
                // Backends load under the gate shared with transcription, so the two never register
                // ggml's CPU backend at the same time (C83).
                NativeBackendGate.shared.ensureLoaded("llama") { LlamaNative.systemInfo(libDir) }
                val ptr = LlamaNative.initContext(modelPath, libDir)
                if (ptr == 0L) error("Could not load the summarisation model")
                isRunning = true
                try {
                    block(Session(ptr))
                } finally {
                    // Cleared before the free rather than after, so a free that throws cannot leave
                    // the app believing a run is still in progress for the rest of the process.
                    isRunning = false
                    LlamaNative.freeContext(ptr)
                }
            }
        }

    /** True when [requestAbort] landed during the most recent run. Cleared by the next one. */
    fun wasAborted(): Boolean = aborted.get()

    /** Asks a run in progress to stop, from any thread. Safe to call when nothing is running. */
    fun requestAbort() {
        aborted.set(true)
        LlamaNative.requestAbort()
    }

    /** A loaded model, valid only inside [withModel]. */
    class Session internal constructor(private val ptr: Long) {

        /** How many tokens [text] actually costs this model's tokeniser. */
        fun countTokens(text: String): Int = LlamaNative.countTokens(ptr, text)

        /**
         * Completes [prompt]. Returns whatever was produced before the stop when aborted.
         *
         * @param grammar GBNF the output must satisfy, or null for free text.
         */
        fun generate(prompt: String, maxTokens: Int, grammar: String? = null): String {
            aborted.set(false)
            warnIfGrammarWouldBeIgnored(grammar)
            val raw = LlamaNative.generate(ptr, prompt, maxTokens, threads, grammar)
            if (aborted.get()) AppLogger.i(TAG, "A summary run was stopped before it finished")
            // Reasoning models think out loud first. That is theirs, not the user's.
            return SummaryText.stripReasoning(raw)
        }

        /**
         * Says so, in the app's own log, when the grammar will be ignored.
         *
         * The native side already falls back to unconstrained generation and logs it — but to logcat,
         * which rotates within minutes and is off by default, so in practice the app produced
         * plausible-looking output with no constraint behind it and nothing to show for it afterwards.
         * That is the shape of failure this project keeps being bitten by: not wrong, but silent.
         *
         * Checked per generate rather than once at load, because it costs a grammar parse against an
         * already-loaded vocabulary — negligible beside the generation that follows — and because the
         * alternative is a cached answer that could be wrong for the grammar actually passed.
         *
         * Only warns. Refusing to generate would turn a degraded summary into no summary, which is the
         * worse of the two.
         */
        private fun warnIfGrammarWouldBeIgnored(grammar: String?) {
            if (grammar.isNullOrEmpty()) return
            val accepted = runCatching { LlamaNative.grammarAccepted(ptr, grammar) }.getOrDefault(true)
            if (!accepted) {
                AppLogger.w(
                    TAG,
                    "The summary grammar failed to parse — this run is UNCONSTRAINED, so the output " +
                        "may not be valid JSON. This is a bug in SummaryGrammar, not in the model."
                )
            }
        }
    }
}
