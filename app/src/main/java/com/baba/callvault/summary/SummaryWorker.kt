/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.transcription.ChineseScript
import com.baba.callvault.transcription.HeavyWorkNotification
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.transcription.TranscriptionProgress
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Summarises one call in the background.
 *
 * Deliberately thin, like [com.baba.callvault.transcription.TranscriptionWorker]: everything worth
 * testing lives in [SummaryRunner], which needs neither WorkManager nor a native library.
 *
 * **One recording per request, never a sweep.** Summarising is roughly ninety seconds of full CPU
 * and gigabytes of memory per call, so draining a library would be hours of heat for pages nobody
 * asked for. See [SummaryQueue].
 */
class SummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /**
     * Asked for by WorkManager when this runs as expedited work, and used by [doWork] otherwise.
     * See [HeavyWorkNotification]: this job holds a multi-gigabyte model for around ninety seconds,
     * which is exactly the shape Android reclaims first at cached-process priority.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        HeavyWorkNotification.forWork(applicationContext, R.string.heavy_work_summarising)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Promote out of cached-process priority before the model is loaded. Best-effort: a phone
        // that refuses the foreground service should still summarise, just more vulnerable to being
        // killed — failing outright here would be worse than the risk it guards against.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { AppLogger.w(TAG, "Could not run summarisation in the foreground: ${it.message}") }

        val displayName = inputData.getString(KEY_DISPLAY_NAME)
            ?: run {
                // Should be impossible — WorkManager always carries the name — but a summary that
                // never happens for no stated reason is the worst possible shape for this to fail in.
                AppLogger.e(TAG, "Summary job started with no recording name; nothing to summarise")
                return@withContext Result.failure()
            }

        val model = SummaryModel.fromId(inputData.getString(KEY_MODEL_ID)) ?: SummaryModel.DEFAULT
        val modelPath = ModelRepository.pathFor(applicationContext, model)?.absolutePath
            ?: run {
                // Retry rather than fail: the model may still be downloading, and 3.46 GB takes a
                // while. The work should pick up when it lands instead of the user asking again.
                AppLogger.i(TAG, "The summariser is not installed yet; will retry")
                return@withContext Result.retry()
            }

        // Checked here as well as at the tap, because a queued job can start much later than it was
        // asked for — by which time a transcription may have begun on the same cores.
        if (TranscriptionEngine.isRunning) {
            AppLogger.i(TAG, "A transcription is running; deferring the summary")
            return@withContext Result.retry()
        }

        val prefs = AppPreferences(applicationContext)

        // Resolved to a concrete language before the prompt is built, never left for the model to
        // infer. "Write in the same language as the conversation" is what produced a Hebrew call
        // summarised into English — on a garbled transcript there is no identifiable main language
        // and a small model resolves that by writing English. See SummaryLanguage.
        val transcript = TranscriptDatabase.get(applicationContext)
            .transcriptDao()
            .observe(displayName)
            .first()
        // Chinese becomes zh-HK / zh-TW on a Hong Kong / Taiwan phone or app, so the prompt asks for Traditional.
        val language = ChineseScript.summaryLanguage(
            SummaryLanguage.resolve(
                chosen = prefs.getSummaryLanguage(),
                transcriptionSetting = prefs.getTranscriptionLanguage(),
                transcriptLanguage = transcript?.transcript?.language,
                transcriptText = transcript?.segments.orEmpty().joinToString(" ") { it.text },
                deviceLanguage = Locale.getDefault().language
            ),
            ChineseScript.effectiveLocale()
        )
        AppLogger.i(TAG, "Summarising in $language")

        AppLogger.i(TAG, "Summarising one recording with ${model.id}")

        // Chunks are the only place the runner can report from, and a chunk is about a minute. The
        // gap between them is far too long to leave a bar still, so the same asymptotic prediction
        // transcription uses fills it: always moving, never arriving, corrected by real progress.
        var completedChunks = 0
        var totalChunks = 0
        var shownPercent = 0
        val startedAt = System.currentTimeMillis()

        suspend fun publish(percent: Int) {
            setProgress(
                workDataOf(
                    KEY_DISPLAY_NAME to displayName,
                    KEY_COMPLETED to completedChunks,
                    KEY_TOTAL to totalChunks,
                    KEY_PERCENT to percent
                )
            )
        }

        val ticker = launch {
            while (isActive) {
                if (isStopped) {
                    AppLogger.i(TAG, "Stopped; asking the model to abort")
                    SummaryEngine.requestAbort()
                    break
                }
                shownPercent = TranscriptionProgress.display(
                    reportedPercent = SummaryProgress.chunkAnchor(completedChunks, totalChunks),
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    estimatedMs = SummaryProgress.estimatedMs(totalChunks),
                    previous = shownPercent
                )
                publish(shownPercent)
                delay(ABORT_POLL_MS)
            }
        }

        val summary = runCatching {
            SummaryRunner(applicationContext).run(
                displayName = displayName,
                modelId = model.id,
                modelPath = modelPath,
                language = language,
                now = System.currentTimeMillis(),
                shouldStop = { isStopped },
                onProgress = { completed, total ->
                    completedChunks = completed
                    totalChunks = total
                }
            )
        }.onFailure { AppLogger.w(TAG, "Summarising failed: ${it.message}") }.getOrNull()

        ticker.cancel()
        // The run is over, so a retry after a failure is a new request, not a double tap (C175).
        SummaryScheduler.requestFinished(displayName)

        when {
            summary != null -> Result.success()
            // Stopped on purpose. Not a failure, and retrying would restart work the user cancelled.
            isStopped -> Result.success()
            else -> Result.failure(workDataOf(KEY_ERROR to ERROR_NO_SUMMARY))
        }
    }

    companion object {
        private const val TAG = "CV:SummaryWorker"

        private const val ABORT_POLL_MS = 400L

        /** Which recording to summarise. Required. */
        const val KEY_DISPLAY_NAME = "displayName"

        /** Which model to use. Absent means the default. */
        const val KEY_MODEL_ID = "modelId"

        /**
         * Progress keys, mirroring the transcription worker's so the UI reads them the same way.
         *
         * The recording's name travels here, as it already does for transcription; the summary text
         * never leaves the database.
         */
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"

        /** The run produced nothing that parsed, so nothing was stored. */
        const val ERROR_NO_SUMMARY = "no-summary"
    }
}
