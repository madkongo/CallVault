/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.transcription.cloud.CloudEndpoint
import com.baba.callvault.transcription.cloud.CloudError
import com.baba.callvault.transcription.cloud.CloudKeyStore
import com.baba.callvault.transcription.cloud.CloudTranscriber
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs transcription in the background, either over the whole backlog or for one recording the user
 * tapped.
 *
 * Deliberately thin: everything worth testing lives in [TranscriptionRunner], which does not need
 * WorkManager or the native library to exercise.
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /**
     * Asked for by WorkManager when this runs as expedited work, and used by [doWork] otherwise.
     * See [HeavyWorkNotification] for why a job this heavy must not run at cached-process priority.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        HeavyWorkNotification.forWork(applicationContext, R.string.heavy_work_transcribing)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Promote out of cached-process priority before allocating anything. Best-effort: a phone that
        // refuses the foreground service should still transcribe, just more vulnerable to being killed
        // — failing the whole job here would be a worse outcome than the risk it guards against.
        suspend fun promote() {
            runCatching { setForeground(getForegroundInfo()) }
                .onFailure { AppLogger.w(TAG, "Could not run transcription in the foreground: ${it.message}") }
        }

        // An automatic run is promoted at once, as it always was. A run the user just confirmed is
        // decided below, once its length is known — see [TranscriptionNoticePolicy].
        val userRequested = inputData.getBoolean(KEY_USER_REQUESTED, false)
        if (!userRequested) promote()

        val prefs = AppPreferences(applicationContext)

        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId())
            ?: TranscriptionModel.DEFAULT
        val single = inputData.getString(KEY_DISPLAY_NAME)

        val engine = when (val chosen = chooseEngine(prefs, model, isNamedRun = single != null)) {
            is EngineChoice.Run -> chosen
            // Retry rather than fail: the model may still be downloading, and the work should pick up
            // once it lands instead of the user having to ask again.
            EngineChoice.WaitForModel -> {
                AppLogger.i(TAG, "Model ${model.id} is not installed yet; will retry")
                return@withContext Result.retry()
            }
            // A sweep with no key set up would mark every waiting call failed; it waits instead.
            EngineChoice.CloudNotSetUp -> {
                AppLogger.i(TAG, "Cloud transcription is chosen but not set up; nothing transcribed")
                return@withContext Result.success()
            }
        }
        val names = if (single != null) {
            listOf(single)
        } else {
            TranscriptionQueue.pending(applicationContext, prefs.getTranscriptionBatchLimit())
        }

        if (names.isEmpty()) {
            AppLogger.i(TAG, "Nothing to transcribe")
            return@withContext Result.success()
        }

        AppLogger.i(TAG, "Transcribing ${names.size} recording(s) with ${engine.modelId}")

        if (userRequested) {
            // A user request always names one recording. Its length is read from the container, which
            // costs milliseconds and happens before the model is loaded.
            val estimatedMs = single?.let { estimatedMsFor(it, prefs) } ?: 0L
            if (TranscriptionNoticePolicy.showsNotification(userRequested = true, estimatedMs = estimatedMs)) {
                AppLogger.i(TAG, "Showing the notification: this run may outlast Android's job limit (~${estimatedMs / 1000}s)")
                promote()
            } else {
                AppLogger.i(TAG, "No notification: the user just confirmed this run and it is short (~${estimatedMs / 1000}s)")
            }
        }

        // Watch for a stop and pass it to whisper. `onStopped` is final on CoroutineWorker, and the
        // batch only consults isStopped *between* recordings — so without this a stop mid-recording
        // would leave the phone at ~600% CPU until that recording finished on its own. Covers the
        // routes stopNow cannot: a lost constraint, or a system-imposed cancellation.
        // Progress has two halves that arrive from different places: which recording of how many
        // (from the runner, between files) and how far through this one (from whisper, continuously).
        // The UI needs both at once, so they are held here and always published together.
        var latestCompleted = 0
        var latestTotal = names.size
        var latestCurrent = ""

        // The percentage is kept here, with the run, rather than on whatever screen is watching.
        // Kept in the composition it used to be, rotating the phone threw away the clock behind it
        // and the figure fell back to 1% and climbed again — which reads as the transcription
        // starting over (issue #34). See [TranscriptionProgressTracker].
        val progress = TranscriptionProgressTracker()

        suspend fun publishProgress(percent: Int) {
            setProgress(
                workDataOf(
                    KEY_COMPLETED to latestCompleted,
                    KEY_TOTAL to latestTotal,
                    KEY_CURRENT to latestCurrent,
                    KEY_PERCENT to percent
                )
            )
        }

        val abortWatcher = launch {
            while (isActive) {
                if (isStopped) {
                    AppLogger.i(TAG, "Stopped; asking whisper to abort")
                    TranscriptionEngine.requestAbort()
                    break
                }
                // Republish on the same tick. The percentage lives in native memory and changes
                // continuously, so it can only reach the UI by being sampled — and this loop was
                // already running, so it costs nothing to add. Whisper reports only at chunk
                // boundaries, so the tracker fills the long gaps between its anchors.
                publishProgress(
                    progress.percent(
                        reportedPercent = engine.progressPercent(),
                        nowMs = SystemClock.elapsedRealtime(),
                    )
                )
                delay(ABORT_POLL_MS)
            }
        }

        val runner = engine.transcriber
            ?.let { TranscriptionRunner(applicationContext, transcriber = it) }
            ?: TranscriptionRunner(applicationContext)
        // A passing cloud failure (429, 5xx, no network) is retried with back-off, a few times only.
        val canRetryLater = engine.isCloud && TranscriptionRouting.mayRetryCloud(runAttemptCount)
        val transcribed = runner.runBatch(
            modelId = engine.modelId,
            modelPath = engine.modelPath,
            language = TranscriptionLanguageChoice.resolve(
                chosen = inputData.getString(KEY_LANGUAGE),
                setting = prefs.getTranscriptionLanguage()
            ),
            displayNames = names,
            shouldStop = { isStopped },
            canRetryLater = canRetryLater,
            onProgress = { completed, total, current ->
                // Remembered as well as published, because the abort watcher republishes the whole
                // set every tick and setProgress replaces rather than merges — dropping these would
                // blank the "2 of 5" the moment the first percentage arrived.
                latestCompleted = completed
                latestTotal = total
                latestCurrent = current
                // A new recording is a new clock; announcing the same one again changes nothing.
                progress.startRecording(
                    displayName = current,
                    estimatedMs = estimatedMsFor(current, prefs),
                    nowMs = SystemClock.elapsedRealtime(),
                )
                publishProgress(progress.percent(reportedPercent = 0, nowMs = SystemClock.elapsedRealtime()))
            }
        )

        abortWatcher.cancel()

        // Anything left unfinished stays queued, so a later run resumes rather than restarts.
        when {
            runner.retryRequested -> Result.retry()
            isStopped && transcribed < names.size -> Result.retry()
            else -> Result.success()
        }
    }

    /** Which engine this run uses, or why it cannot run yet. */
    private sealed interface EngineChoice {
        data class Run(
            val modelId: String,
            val modelPath: String,
            /** Null for the default on-device engine. */
            val transcriber: Transcriber?,
            val isCloud: Boolean,
            val progressPercent: () -> Int,
        ) : EngineChoice

        data object WaitForModel : EngineChoice
        data object CloudNotSetUp : EngineChoice
    }

    /**
     * On this phone with the installed model, or the chosen cloud service with its key (issue #48).
     *
     * A cloud service never waits for a model. Not set up (no key, or an incomplete Custom): a named
     * run still goes ahead so that one recording fails with a message saying what to fix; a sweep
     * does nothing, rather than fail every waiting call.
     */
    private fun chooseEngine(prefs: AppPreferences, model: TranscriptionModel, isNamedRun: Boolean): EngineChoice {
        val service = prefs.getTranscriptionService()
        val modelPath = if (service.isCloud) null else ModelRepository.pathFor(applicationContext, model)?.absolutePath
        return when (TranscriptionRouting.route(service, modelInstalled = modelPath != null)) {
            TranscriptionRouting.Route.WAIT_FOR_MODEL -> EngineChoice.WaitForModel
            TranscriptionRouting.Route.ON_DEVICE -> EngineChoice.Run(
                model.id, modelPath.orEmpty(), transcriber = null, isCloud = false,
                progressPercent = { TranscriptionEngine.progressPercent() },
            )
            TranscriptionRouting.Route.CLOUD -> {
                val endpoint = prefs.getCloudEndpoint(service)
                val key = CloudKeyStore(applicationContext).get(service)
                if ((endpoint == null || key == null) && !isNamedRun) return EngineChoice.CloudNotSetUp
                val label = endpoint?.label ?: service.preset?.displayName ?: service.key
                val transcriber = endpoint?.let { CloudTranscriber(it, key).asTranscriber() }
                    ?: Transcriber { _, _, _, _, _, _, _ -> throw CloudError.notSetUp(label) }
                EngineChoice.Run(
                    modelId = endpoint?.engineId ?: "${CloudEndpoint.ENGINE_PREFIX}${service.key}",
                    modelPath = "",
                    transcriber = transcriber,
                    isCloud = true,
                    progressPercent = { CloudTranscriber.progressPercent },
                )
            }
        }
    }

    /**
     * How long [displayName] is expected to take on this phone, or 0 when that cannot be known.
     *
     * Read from the container rather than from the catalog's duration column: that column comes from
     * matching the call log, and plenty of recordings never match — for those an estimate built on it
     * would be zero, prediction would be off, and the figure would sit on whisper's anchors, which is
     * the stuck-looking percentage the prediction exists to cure. The container knows its own length
     * regardless, and it is the same source the confirmation dialog quotes, so the two agree.
     */
    private suspend fun estimatedMsFor(
        displayName: String,
        prefs: AppPreferences,
    ): Long {
        val uri = RecordingCatalog.all(applicationContext)
            .firstOrNull { it.displayName == displayName }
            ?.localUri
            ?.toUri()
            ?: return 0L
        val audioMs = runCatching { AudioDecoder.durationMs(applicationContext, uri) }.getOrDefault(0L)
        return if (audioMs <= 0L) 0L else TranscriptionEstimate.estimateMs(audioMs, TranscriptionRouting.runCost(prefs))
    }

    companion object {
        private const val TAG = "CV:TranscriptionWorker"

        /** How often the abort watcher checks for a stop. Cheap, and a stop should feel instant. */
        private const val ABORT_POLL_MS = 400L

        /** Input key naming a single recording, for the manual button. Absent means "drain the queue". */
        const val KEY_USER_REQUESTED = "userRequested"

        const val KEY_DISPLAY_NAME = "displayName"

        /**
         * Input key carrying the language picked for this one recording, encoded by
         * [TranscriptionLanguageChoice.encode]. Absent means "use the setting".
         */
        const val KEY_LANGUAGE = "language"

        /**
         * Progress keys read by Home's transcribing pill.
         *
         * Published rather than logged: a run can last hours, and the only alternative to showing it
         * is a phone that is inexplicably warm. The name is the recording's, which the runner already
         * logs — the transcript *text* is never put anywhere outside the database.
         */
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"

        /** How far through the current recording, 0-100. Absent or 0 means "not known yet". */
        const val KEY_PERCENT = "percent"
    }
}
