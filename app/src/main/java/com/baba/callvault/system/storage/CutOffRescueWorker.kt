/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.app.usage.UsageStatsManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
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
import com.baba.callvault.services.recording.aftercall.AfterCallDecision
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Saves the part of a call that was recorded before the phone killed CallVault (backlog #4 + #5).
 *
 * A recording is staged in private storage and published only when the call ends. If the app process dies
 * mid-call, the publish never happens: a tester's OnePlus killed CallVault 13 minutes into a call on
 * 2026-09-28 and the audio sat where he could not reach it, with no word that anything was lost.
 *
 * Runs [DELAY_MS] after the app starts and after every call ends — the app can be restarted *during* the
 * call it lost, so a start-up check alone would see the file still in use and skip it for good.
 * [CutOffRescuePolicy] decides; this only does the file work. It never deletes a file that holds audio:
 * a failed publish leaves the staged file where it is, for the next run.
 */
class CutOffRescueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // N37: an app idle in the rare or restricted bucket can lose network, loopback included. Say which bucket the
        // job ran in, so a report of a rescue that could not reach the recorder host can be read against it.
        logStandbyBucket(applicationContext)
        // First: a recorder still recording for a process that is gone keeps its file "in use" for ever, so
        // nothing here could ever save it (C2). Stopped when its call is over; kept when it may be live (Q25).
        runCatching { com.baba.callvault.services.recording.OrphanRecorderSession.settle(applicationContext, "rescue check") }
            .onFailure { AppLogger.e(TAG, "Orphan recorder check failed: ${it.message}", it) }
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
        val owned = LiveStagingFiles.isLive(file)
        val decision = CutOffRescuePolicy.decide(
            hasNote = note != null,
            sizeBytes = file.length(),
            ageMs = System.currentTimeMillis() - file.lastModified(),
            callActive = callActive,
            ownedByLiveRecording = owned,
        )
        when (decision) {
            RescueDecision.WAIT -> AppLogger.d(
                TAG,
                if (owned) "${file.name} belongs to a recording still running here (paused, or a call with no call mode); leaving it"
                else "${file.name} may still be in use; looking again later",
            )
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
            // Review 2026-11-20: retrying the folder its note names for ever is what kept a call from a lost folder out
            // of reach. Moved into waiting storage instead, it is saved into whichever folder is usable later, counted on
            // Home and in the alert. If the move fails it stays here for the next try, as before.
            if (WaitingRecordings.adopt(applicationContext, file, fileName, mimeType, "cut-off recording", finished = true) != null) {
                AppLogger.w(TAG, "Could not save the cut-off recording ${file.name} as '$fileName'; moved into waiting storage")
                return
            }
            AppLogger.e(TAG, "Could not save the cut-off recording ${file.name} as '$fileName'; keeping it for the next try")
            return
        }
        AppLogger.w(TAG, "Saved a recording cut off when CallVault was closed: '$fileName' ($bytes bytes) -> $uri")
        val cutAt = file.lastModified()
        runCatching { file.delete(); noteFile.delete() }
        afterPublished(applicationContext, fileName, uri, mimeType, bytes, startedAt, cutAt)
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

        /** The Drive copy, details-file copy and transcription a normal call gets after it is saved. A seam for tests. */
        internal val defaultHandOffs: (Context, String, Uri, String) -> Unit = AfterCallDecision::runHeldHandOffs
        internal var handOffs: (Context, String, Uri, String) -> Unit = defaultHandOffs

        /**
         * Everything that follows a successful publish. Into the library (Home's list follows the catalog live), into
         * the health card (the call this belongs to is no longer "not recorded", it was cut off), and then the hand-offs
         * every other saved call gets: without them a rescued recording in Drive mode was never copied and was later
         * aged out as the only copy, and never transcribed (review C46). Each hand-off is on its own inside
         * [AfterCallDecision.runHeldHandOffs]; a failure here is logged and never undoes the save.
         */
        internal suspend fun afterPublished(
            context: Context, fileName: String, uri: Uri, mimeType: String, bytes: Long, startedAt: Long?, cutAt: Long,
        ) {
            RecordingCatalog.recordLocal(context, fileName, uri, bytes, startedAt ?: cutAt)
            recordSaved(context, fileName, startedAt, cutAt)
            runCatching { handOffs(context, fileName, uri, mimeType) }
                .onSuccess { CallEndLog.record(context, "cut-off rescue: '$fileName' handed to Drive copy and transcription") }
                .onFailure {
                    CallEndLog.record(context, "cut-off rescue: hand-off of '$fileName' FAILED ${CallEndLog.describe(it)}; it stays in the library")
                    AppLogger.w(TAG, "Hand-off of the rescued recording failed: ${it.message}")
                }
        }

        /** Whether the staging folder holds anything the rescue could act on: a staged recording or a leftover note. */
        internal fun hasStagedWork(stagingDir: File?): Boolean =
            stagingDir?.listFiles().orEmpty().any { SafHelper.isStagedRecording(it) || it.name.endsWith(StagingNote.SUFFIX) }

        /**
         * App start (review R17): queue the rescue only when something is staged. Queuing it unconditionally made a
         * phone that kills cached apps within minutes restart CallVault every two minutes, for a job with nothing to
         * do. Call end and the orphan-recorder check still queue it themselves. If the folder cannot be read, queue
         * as before.
         */
        fun scheduleAtStartIfNeeded(context: Context) {
            val needed = runCatching { hasStagedWork(SafHelper.stagingDirOrNull(context)) }.getOrDefault(true)
            if (needed) schedule(context) else AppLogger.d(TAG, "Nothing staged at start; rescue check not queued")
        }

        internal fun standbyBucketName(bucket: Int): String = when (bucket) {
            10 -> "ACTIVE"
            20 -> "WORKING_SET"
            30 -> "FREQUENT"
            40 -> "RARE"
            45 -> "RESTRICTED"
            50 -> "NEVER"
            else -> "unknown($bucket)"
        }

        private fun logStandbyBucket(context: Context) {
            val bucket = runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@runCatching "n/a"
                standbyBucketName(context.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
            }.getOrElse { "unreadable" }
            CallEndLog.record(context, "cut-off rescue job started (standby bucket $bucket)")
        }

        /**
         * After a cut-off recording is in the library: the health card (the call it belongs to was cut off, not "not
         * recorded") and the notice. Shared with the waiting publisher, which saves a cut-off the same way (F3).
         */
        fun recordSaved(context: Context, fileName: String, startedAt: Long?, cutAt: Long) {
            val call = CutOffRescuePolicy.matchCall(
                CallLogReader.entriesSince(context, cutAt - CALL_LOOKBACK_MS), startedAt, cutAt,
            )
            AppLogger.i(TAG, "The cut-off recording belongs to ${call?.let { "the call at ${it.startedAt}" } ?: "no call in the call log"}")
            runCatching {
                SetupHealthStore(context).recordCutOff(
                    atMillis = System.currentTimeMillis(),
                    callStartedAt = call?.startedAt,
                    callEndedAt = call?.let { it.startedAt + it.durationSeconds * 1_000L },
                    label = call?.label,
                )
            }.onFailure { AppLogger.w(TAG, "Could not update the status card: ${it.message}") }
            SilentFailureNotifier.noteCutOffRecordingSaved(context, fileName)
        }
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
