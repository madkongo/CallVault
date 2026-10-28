/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.waveform

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Draws one recording's waveform in the background and caches it.
 *
 * Drawing means decoding every sample, and Android's Opus decoder runs at roughly 12-15x real time
 * on an OnePlus 9 — about 3.5 minutes for a 45-minute call (issue #63, measured 2026-10-28). Run
 * from a coroutine, that work died with whatever owned it: leaving the recording's page cancelled
 * it, and the call-end draw ran in a bare scope that Android kills with the process once the call
 * service stops. A long call therefore never got its bars. As a WorkManager job it survives both,
 * and the open page shows the bars the moment they are saved ([RecordingExtrasRepository.observeWaveform]).
 *
 * All draws share ONE queue and run one at a time: this is background work behind whatever the user
 * is doing, and several decodes at once would compete with playback and transcription for the cores.
 */
class WaveformDrawWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.success()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.success()

        val audio = applicationContext.getSystemService(AudioManager::class.java)
        val decision = WaveformDrawPolicy.decide(
            callInProgress = audio != null && audio.mode != AudioManager.MODE_NORMAL,
            transcribing = TranscriptionEngine.isRunning,
        )
        if (decision == WaveformDrawPolicy.Decision.WAIT) {
            AppLogger.i(TAG, "Waiting to draw '$name': a call or a transcription is running")
            return Result.retry()
        }

        RecordingExtrasRepository.precomputeWaveform(applicationContext, name, uri)
        return Result.success()
    }

    companion object {
        private const val TAG = "CV:WaveformDraw"
        private const val QUEUE_NAME = "waveform_draws"
        private const val KEY_NAME = "display_name"
        private const val KEY_URI = "uri"
        private const val RETRY_BACKOFF_SECONDS = 30L

        /**
         * Queues a draw of [displayName]. Cheap to call more than once: the job checks the cache
         * first, so a recording already drawn costs one indexed lookup.
         */
        fun enqueue(context: Context, displayName: String, uri: Uri) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<WaveformDrawWorker>()
                    .setInputData(workDataOf(KEY_NAME to displayName, KEY_URI to uri.toString()))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
                // APPEND_OR_REPLACE: one queue, one draw at a time; a failed or cancelled job ahead
                // of this one does not leave the queue stuck.
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not queue the drawing of '$displayName': ${it.message}") }
        }
    }
}

/** When a background draw may run. Pure, so it can be tested without a phone. */
internal object WaveformDrawPolicy {

    enum class Decision { DRAW, WAIT }

    /**
     * Never during a call: the one job this app must not disturb is the recording happening now.
     * Never during a transcription: it owns the CPU, and drawing alongside it inflates the time its
     * estimate learns from.
     */
    fun decide(callInProgress: Boolean, transcribing: Boolean): Decision =
        if (callInProgress || transcribing) Decision.WAIT else Decision.DRAW
}
