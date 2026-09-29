/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.data.health.CallLogReader
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Saves the part of a call that was recorded before the phone killed CallVault (backlog #4 + #5).
 *
 * A recording is staged in private storage and published only when the call ends. If the app process dies
 * mid-call, the publish never happens: voarch's OnePlus killed CallVault 13 minutes into a call on
 * 2026-09-28 and the audio sat where he could not reach it, with no word that anything was lost.
 *
 * Runs [DELAY_MS] after the app starts and after every call ends — the app can be restarted *during* the
 * call it lost, so a start-up check alone would see the file still in use and skip it for good.
 * [CutOffRescuePolicy] decides; this only does the file work. It never deletes a file that holds audio:
 * a failed publish leaves the staged file where it is, for the next run.
 */
class CutOffRescueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val dir = SafHelper.stagingDirOrNull(applicationContext) ?: return Result.success()
        val files = dir.listFiles().orEmpty()
        // A note whose recording is gone was left by a normal finish (callers delete only the staged file).
        files.filter { it.name.endsWith(StagingNote.SUFFIX) && !File(dir, it.name.removeSuffix(StagingNote.SUFFIX)).exists() }
            .forEach { runCatching { it.delete() } }
        val staged = files.filter { SafHelper.isStagedRecording(it) }
        if (staged.isEmpty()) return Result.success()
        val callActive = isCallActive()
        var waiting = false
        staged.forEach { file -> if (rescue(file, callActive) == RescueDecision.WAIT) waiting = true }
        // Something may still be in use; look again once it has had time to go quiet.
        if (waiting) schedule(applicationContext)
        return Result.success()
    }

    private suspend fun rescue(file: File, callActive: Boolean): RescueDecision {
        val noteFile = StagingNote.fileFor(file)
        val note = noteFile.takeIf { it.exists() }?.let { runCatching { StagingNote.decode(it.readText()) }.getOrNull() }
        val decision = CutOffRescuePolicy.decide(
            hasNote = note != null,
            sizeBytes = file.length(),
            ageMs = System.currentTimeMillis() - file.lastModified(),
            callActive = callActive,
        )
        when (decision) {
            RescueDecision.WAIT -> AppLogger.d(TAG, "${file.name} may still be in use; looking again later")
            RescueDecision.DISCARD_EMPTY -> {
                AppLogger.i(TAG, "${file.name} is empty; removing it")
                runCatching { file.delete(); noteFile.delete() }
            }
            RescueDecision.SAVE_NAMED -> note?.let { publish(file, noteFile, it.folderUri, it.fileName, it.mimeType, it.startedAtMillis) }
            // No note beside it: we cannot tell an interrupted recording from a normally-finished call's
            // orphaned temp, so we say nothing and touch nothing. Left in place (may hold old-build audio).
            RescueDecision.LEAVE_UNATTRIBUTED ->
                AppLogger.d(TAG, "${file.name} has no recovery note; leaving it, not reporting a cut-off")
        }
        return decision
    }

    private suspend fun publish(file: File, noteFile: File, folderUri: String, fileName: String, mimeType: String, startedAt: Long?) {
        val bytes = file.length()
        val uri = SafHelper.publishStagedRecording(applicationContext, folderUri.toUri(), fileName, mimeType, file)
        if (uri == null) {
            AppLogger.e(TAG, "Could not save the cut-off recording ${file.name} as '$fileName'; keeping it for the next try")
            return
        }
        AppLogger.w(TAG, "Saved a recording cut off when CallVault was closed: '$fileName' ($bytes bytes) -> $uri")
        val cutAt = file.lastModified()
        runCatching { file.delete(); noteFile.delete() }
        // Into the library (Home's list follows the catalog live), and into the health card: the call
        // this belongs to is no longer "not recorded", it was cut off.
        RecordingCatalog.recordLocal(applicationContext, fileName, uri, bytes, startedAt ?: cutAt)
        val call = CutOffRescuePolicy.matchCall(
            CallLogReader.entriesSince(applicationContext, cutAt - CALL_LOOKBACK_MS), startedAt, cutAt,
        )
        AppLogger.i(TAG, "The cut-off recording belongs to ${call?.let { "the call at ${it.startedAt}" } ?: "no call in the call log"}")
        runCatching {
            SetupHealthStore(applicationContext).recordCutOff(
                atMillis = System.currentTimeMillis(),
                callStartedAt = call?.startedAt,
                callEndedAt = call?.let { it.startedAt + it.durationSeconds * 1_000L },
                label = call?.label,
            )
        }.onFailure { AppLogger.w(TAG, "Could not update the status card: ${it.message}") }
        SilentFailureNotifier.noteCutOffRecordingSaved(applicationContext, fileName)
    }

    private fun isCallActive(): Boolean = runCatching {
        val mode = applicationContext.getSystemService(AudioManager::class.java)?.mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        val carrier = applicationContext.getSystemService(TelephonyManager::class.java)?.callState
        inCall || carrier == TelephonyManager.CALL_STATE_OFFHOOK
    }.getOrDefault(true) // unknown reads as "in a call": waiting costs nothing, touching a live file could

    companion object {
        private const val TAG = "CV:CutOffRescue"
        private const val WORK_NAME = "cut_off_rescue"

        /** How far back the call log is read to find the cut-off call: longer than any plausible call. */
        private const val CALL_LOOKBACK_MS = 12 * 60 * 60_000L

        /** A little over [CutOffRescuePolicy.QUIET_MS], so a file abandoned just now is quiet by the time we look. */
        private const val DELAY_MS = CutOffRescuePolicy.QUIET_MS + 30_000L

        /** Looks for cut-off recordings shortly. Safe to call often: the latest request replaces earlier ones. */
        fun schedule(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<CutOffRescueWorker>()
                    .setInitialDelay(DELAY_MS, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not schedule the cut-off recording check: ${it.message}") }
        }
    }
}
