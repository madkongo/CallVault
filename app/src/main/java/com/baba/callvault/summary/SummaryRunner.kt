/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.server.speakers.SpeakerChannel
import com.baba.callvault.transcription.ChineseScript
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.first

/** A loaded model, for the length of one run. Substituted in tests. */
fun interface SummarySession {
    fun generate(prompt: String, maxTokens: Int, grammar: String?): String
}

/**
 * Loads a model, runs [block] against it, and frees it.
 *
 * An interface rather than a direct call to [SummaryEngine] so the runner can be tested without a
 * native library, a 3.46 GB file, or ninety seconds per case.
 */
interface SummaryModelHost {
    suspend fun run(modelPath: String, block: suspend (SummarySession) -> CallSummary?): CallSummary?

    companion object {
        /**
         * The real one. Loads through [SummaryEngine], which owns the mutex and the free.
         *
         * A factory rather than the object it used to be because loading now needs the app's own
         * library directory, which only a [Context] can give — see [LlamaNative].
         */
        fun default(context: Context) = object : SummaryModelHost {
            override suspend fun run(
                modelPath: String,
                block: suspend (SummarySession) -> CallSummary?
            ): CallSummary? = SummaryEngine.withModel(context, modelPath) { session ->
                block { prompt, maxTokens, grammar -> session.generate(prompt, maxTokens, grammar) }
            }
        }
    }
}

/**
 * Turns a stored transcript into a stored summary.
 *
 * Separate from the worker for the same reason [com.baba.callvault.transcription.TranscriptionRunner]
 * is: everything worth testing lives here, and none of it needs WorkManager or a native library.
 *
 * **The model is loaded once for the whole call, not once per chunk.** Loading is seconds and the
 * memory is gigabytes; a long call is several chunks plus a merge, and paying the load for each
 * would dominate the run.
 */
class SummaryRunner(
    private val context: Context,
    private val host: SummaryModelHost = SummaryModelHost.default(context),
    /** Whether the run that just ended was stopped rather than finished. Injected for tests. */
    private val wasAborted: () -> Boolean = { SummaryEngine.wasAborted() }
) {

    private val db get() = TranscriptDatabase.get(context)

    /**
     * Summarises [displayName] and stores the result.
     *
     * @param shouldStop consulted between chunks. A single chunk can take a minute, so this is the
     *   granularity a stop can be honoured at without abandoning the model mid-token; the worker
     *   also calls [SummaryEngine.requestAbort], which reaches inside a running generate.
     * @param onProgress how many chunks are done out of how many, so something on screen can move.
     * @return the stored summary, or null if there was nothing to summarise or the run was stopped.
     */
    suspend fun run(
        displayName: String,
        modelId: String,
        modelPath: String,
        language: String?,
        now: Long,
        shouldStop: () -> Boolean = { false },
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): CallSummary? {
        val stored = segmentsFor(displayName)
        val segments = stored.named(displayName)
        val speakersAreNamed = segments !== stored
        if (segments.isEmpty()) {
            // Guarded by SummaryQueue too, but a summary of nothing costs the same as a real one.
            AppLogger.i(TAG, "Nothing to summarise for a recording with no transcribed words")
            return null
        }

        val chunks = SummaryChunking.chunk(segments)
        onProgress(0, chunks.size)

        val summary = host.run(modelPath) { session ->
            val parts = mutableListOf<String>()

            chunks.forEachIndexed { index, chunk ->
                if (shouldStop()) return@run null
                parts += session.generate(
                    SummaryPrompt.forChunkJson(
                        chunk, language, withTimestamps = true, speakersAreNamed = speakersAreNamed
                    ),
                    CHUNK_TOKEN_BUDGET,
                    SummaryGrammar.JSON
                )
                onProgress(index + 1, chunks.size)
            }

            if (shouldStop() || wasAborted()) return@run null

            val parsedParts = parts.mapNotNull(CallSummary::parse)
            if (parsedParts.size < parts.size) {
                // Named precisely, because the two ways to get nothing look identical from outside
                // and need different fixes. A part that will not parse used to mean the generation
                // had hit its token budget; that case is now closed by hand and kept, so what is
                // left here is a cut that took the prose with it, or output that was never an
                // object at all.
                AppLogger.w(TAG, "${parts.size - parsedParts.size} of ${parts.size} part(s) did not parse")
            }

            // One chunk is already the answer. Merging it with itself would compress a summary that
            // has already been compressed once, and measurably loses detail.
            val merged = if (parsedParts.size <= 1) {
                parsedParts.firstOrNull()
            } else {
                val document = session.generate(
                    SummaryPrompt.forMergeJson(parts, language),
                    MERGE_TOKEN_BUDGET,
                    SummaryGrammar.JSON
                )
                // The merge is a second generation and so a second chance to fail. Losing every
                // part to it would mean minutes of work the user watched, ending in nothing —
                // so a merge that will not parse falls back to the parts that did.
                CallSummary.parse(document) ?: run {
                    AppLogger.w(TAG, "The merge did not parse; falling back to the parts")
                    CallSummary.concatenate(parsedParts)
                }
            }

            merged?.let { parsed ->
                // The prompt asks for timestamps "copied verbatim" and tells the model never to
                // invent one. It invents them anyway — and here they are tappable seek targets, so
                // a fabricated marker is a false citation on the one surface whose value is being
                // checkable against the recording. An instruction is a request; this is not.
                val checked = SummaryCitations.strip(
                    summary = parsed,
                    offeredMs = segments.map { it.startMs }.toSet(),
                    durationMs = segments.maxOfOrNull { it.endMs } ?: 0L
                )
                if (checked.removed.isNotEmpty()) {
                    // Loud on purpose. A stripped summary looks perfectly clean, so without this
                    // nothing would ever say the model had started inventing citations.
                    AppLogger.w(
                        TAG,
                        "Dropped ${checked.removed.size} uncited timestamp(s): " +
                            checked.removed.toSet().joinToString(" ")
                    )
                }
                // Last, and after the citations, so an item stripped down to bare text is still
                // compared against its neighbours. Observed on the first real Hebrew summary: four
                // decisions, three of them the same sentence.
                // Chinese on a Hong Kong / Taiwan phone, and Cantonese, are written in Traditional characters
                // whatever the model answered in (BabApps item 125). Before the dedupe, so the compare sees final text.
                val conversion = ChineseScript.forSummary(language)
                val written = if (conversion == null) checked.summary
                else checked.summary.mapText { ChineseScript.convert(it, conversion) }
                SummaryDedupe.apply(written)
            }
        }

        if (summary == null) {
            // Never store a half-summary. An aborted run returns whatever was produced before the
            // stop, which parses to a fragment or to nothing; either way it is not an account of
            // the call and showing it as one would be worse than showing nothing.
            AppLogger.i(TAG, "No summary stored: the run was stopped or produced nothing usable")
            return null
        }

        db.summaryDao().upsert(
            CallSummaryEntry(
                displayName = displayName,
                document = summary.toJson(),
                searchText = summary.searchableText(),
                model = modelId,
                createdAt = now
            )
        )
        AppLogger.i(TAG, "Stored a summary of ${chunks.size} chunk(s)")
        return summary
    }

    /**
     * Replaces the stored `A`/`B` labels with real names, for the model's eyes only.
     *
     * Nothing is written back: the database keeps the neutral labels, because which channel is whose
     * is learned over time and can be un-learned. This is the same resolution the transcript screen
     * does, at the same moment — on read.
     *
     * Returns the list unchanged, identically, when there is nothing to name. The caller compares
     * identity to decide what to tell the model, so the two can never disagree.
     */
    private suspend fun List<TranscriptSegmentEntry>.named(
        displayName: String
    ): List<TranscriptSegmentEntry> {
        if (none { it.speaker != null }) return this

        // Neutral labels are left exactly as they are. Even unnamed they earn their place: they tell
        // the model where the turns are, which is why the transcript keeps its line breaks at all.
        val channelMap = SpeakerTurnsRepository.trustedMap(context)
        if (channelMap == ChannelMap.UNKNOWN) return this

        val names = SpeakerNames(
            map = channelMap,
            // English, like every other word of this prompt. The summary's own language is pinned
            // separately and far more firmly than one label could push it.
            you = "You",
            contact = contactNameFor(displayName),
            sideA = SpeakerChannel.A.key,
            sideB = SpeakerChannel.B.key
        )
        return map { segment -> segment.copy(speaker = names.of(segment.speaker) ?: segment.speaker) }
    }

    /**
     * Who the call was with, in words.
     *
     * Best-effort and never fatal: without the contacts permission, or for a number nobody has
     * saved, the model is told "the other person" — which is true, and better than quoting a phone
     * number back as though it were a fact worth keeping.
     */
    private suspend fun contactNameFor(displayName: String): String =
        runCatching {
            RecordingsRepository.listRecordings(context)
                .firstOrNull { it.displayName == displayName }
                ?.contactName
        }.getOrNull().orEmpty().ifBlank { "the other person" }

    /** The transcribed words, in order. Empty when there is no transcript. */
    private suspend fun segmentsFor(displayName: String): List<TranscriptSegmentEntry> {
        if (!TranscriptDatabase.exists(context)) return emptyList()
        return db.transcriptDao().observe(displayName).first()?.segments.orEmpty()
    }

    private companion object {
        const val TAG = "CV:SummaryRunner"

        /**
         * Tokens allowed for one chunk's JSON.
         *
         * Raised from 420 to 900 after a real 6:52 Hebrew call produced nothing at all: the grammar
         * keeps a truncated document from looking plausible, but it still truncates, and a document
         * cut short was refused on the way out — so the whole run was lost. Six keys of Hebrew cost
         * far more tokens than the same summary in English, and 420 sat right on the edge.
         *
         * Raised again, to 1,600, because the prompt now asks for up to eight items a list instead
         * of five and no longer asks for each to be one short line. That is roughly twice the
         * content, and 900 was itself only about twice the 420 that failed — so leaving it would put
         * a long Hebrew answer back on the same edge, which is the whole reason 420 is a footnote
         * here rather than the current value.
         *
         * **Raise this before raising the item cap, never after.** The two are one change: more
         * items against an unchanged budget is the 420 cliff, re-opened.
         *
         * Two things make the new figure a cost rather than a gamble. Generation stops when the
         * model closes the object, so the cap binds only on an answer that genuinely used it; and
         * [SummaryGrammar] now bounds every list by construction, so the worst case is finite — the
         * old budget was partly insurance against an unbounded array running away. What it does buy
         * is a bigger context (`n_ctx` is the prompt plus this) and a longer wait on a verbose
         * answer, both of which a background job can afford.
         */
        const val CHUNK_TOKEN_BUDGET = 1_600

        /** The merge carries every part's points forward, so it needs more room than one chunk. */
        const val MERGE_TOKEN_BUDGET = 2_000
    }
}
