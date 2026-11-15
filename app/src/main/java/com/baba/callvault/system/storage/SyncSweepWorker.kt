/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.services.recording.aftercall.AfterCallDecisions
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy
import android.content.Context
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import com.baba.callvault.transcription.HeavyWorkNotification
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.utils.AppLogger

/**
 * Batch/scheduled equivalent of [RecordingCopyWorker]. Walks the device recording folder and copies
 * every audio file that is not yet present (by display name) in the Drive folder, using the same
 * [SafHelper.copyFileToFolder] mechanism. After a successful copy it deletes the local source when the
 * storage target is [StorageTarget.DRIVE] (DRIVE = cloud only); [StorageTarget.BOTH] keeps the local copy.
 *
 * Robust by design: no-op when folders are unconfigured or the target is LOCAL; individual file errors
 * are logged and skipped; a copy failure yields [Result.retry] so WorkManager backoff re-runs the sweep.
 */
class SyncSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val sweepStart = SystemClock.elapsedRealtime()
        val prefs = AppPreferences(applicationContext)
        val target = prefs.getStorageTarget()
        if (target == StorageTarget.LOCAL) {
            AppLogger.i(TAG, "Storage target is LOCAL; nothing to sweep.")
            return Result.success()
        }

        val sourceFolderUri = prefs.getRecordingFolderUri()
        val driveFolderUri = prefs.getDriveFolderUri()
        if (sourceFolderUri == null || driveFolderUri == null) {
            AppLogger.w(TAG, "Sweep skipped: source or Drive folder not configured (source=$sourceFolderUri drive=$driveFolderUri).")
            return Result.success()
        }
        if (FolderOverlap.overlaps(sourceFolderUri, driveFolderUri)) {
            // Copying a folder into itself (or into a folder inside it) only ever finds the recordings
            // themselves, and Drive-only would then delete them. See [FolderOverlap].
            AppLogger.e(TAG, "Sweep skipped: the Drive folder is the recording folder or overlaps it.")
            return Result.success()
        }
        val sourceDir = DocumentFile.fromTreeUri(applicationContext, sourceFolderUri)
        if (sourceDir == null || !sourceDir.exists()) {
            AppLogger.w(TAG, "Source recording folder inaccessible; nothing to sweep.")
            return Result.success()
        }

        // Everything that talks to Google Drive's provider happens in the copier process (see
        // [DriveCopyService]): when Drive's process dies Android kills the process that holds a connection
        // to it, and that must not be this one. From here on this process never queries Drive.
        val copier = DriveCopyClient(applicationContext)
        try {
            return sweep(copier, target, sourceDir, driveFolderUri, sweepStart)
        } finally {
            copier.close()
        }
    }

    private suspend fun sweep(
        copier: DriveCopyClient,
        target: StorageTarget,
        sourceDir: DocumentFile,
        driveFolderUri: android.net.Uri,
        sweepStart: Long,
    ): Result {
        // What Drive already holds (name -> its URI and byte length, case-insensitive), so a
        // recording that is already up there is never uploaded a second time. One query, in the copier.
        //
        // The URI is kept, not just the length: proving a file is already in Drive is exactly the
        // evidence the catalog needs, and throwing it away is what let a correctly backed-up
        // recording go on looking unsynced forever. See where `alreadyThere` is used.
        val listed = when (val o = copier.call(DriveCopyProtocol.MSG_LIST, DriveCopyProtocol.listRequest(driveFolderUri), LIST_TIMEOUT_MS)) {
            is DriveCopyClient.Outcome.Reply -> DriveCopyProtocol.readListing(o.data)
            else -> null
        }
        if (listed == null) {
            AppLogger.w(TAG, "Drive folder not currently valid/writable (or Drive did not answer); retrying later.")
            DriveCopyJournal.record(applicationContext, "sweep not started: the Drive folder could not be listed")
            return Result.retry()
        }
        val existingInDrive = HashMap<String, DriveCopy>(listed.names.size * 2)
        for (i in listed.names.indices) {
            existingInDrive[listed.names[i].lowercase()] = DriveCopy(listed.uris[i], listed.sizes[i])
        }
        DriveCopyJournal.record(applicationContext, "drive folder listed: ${listed.names.size} entries in ${SystemClock.elapsedRealtime() - sweepStart}ms (incl. start-up)")

        val deleteLocal = target == StorageTarget.DRIVE
        var copied = 0
        var failures = 0
        var skipped = 0
        // First pass: what Drive already has is stamped; the rest is collected and uploaded below, newest first.
        val pending = ArrayList<DriveSweepOrder.Pending<SweepFile>>()
        // Recordings still waiting for a keep-or-delete answer, read once per run. Neither they nor their
        // sidecars go to Drive until the user says keep: copying one they then delete would leave the call
        // in the cloud, and under DRIVE-only the copy would take it off the phone before they had answered.
        val heldNames = AfterCallDecisions.pendingNames(applicationContext)
        for (file in sourceDir.listFiles()) {
            val name = file.name ?: continue
            if (!file.isFile) continue
            // A dot-file is never a recording. The one CallVault itself puts here is Hide Recordings'
            // .nomedia, which the media index fills with the folder path — so it is not empty, and in
            // Drive-only mode this sweep would upload it and delete it from the phone, un-hiding the
            // recordings it was put there to hide.
            if (name.startsWith(".")) continue
            // A staging document from an interrupted copy is not a recording.
            if (CloudCopyPolicy.isStagingName(name)) continue
            // Nor is a file the user imported: their own audio, which in DRIVE-only mode this sweep
            // would upload and then delete from the phone. See [CloudCopyPolicy.mayGoToCloud].
            if (!CloudCopyPolicy.mayGoToCloud(name)) continue
            if (AfterCallPolicy.isHeldFile(name, heldNames)) continue

            val sourceSize = runCatching { file.length() }.getOrDefault(-1L)
            if (sourceSize <= 0L) {
                skipped++
                continue // an empty file means capture never ran; copying it would fake a backup
            }
            val alreadyThere = existingInDrive[name.lowercase()]
            if (alreadyThere != null &&
                CloudCopyPolicy.verdict(alreadyThere.sizeBytes, sourceSize) == ExistingCopyVerdict.COMPLETE
            ) {
                // Stamp it rather than only skipping the upload. We have just read the Drive folder
                // and established that this recording IS backed up; if the catalog row says
                // otherwise, this is the only place that can ever put it right — every later sweep
                // takes this same branch and would skip again.
                //
                // Without it the health check saw a device copy with no Drive copy, waited out the
                // staleness window and told the user "recordings are not reaching Drive" about
                // recordings sitting safely in Drive. Reported by two users on 2.2.0.
                //
                // deleteLocalAfter = false, and the device file is deliberately left alone. Removing
                // it would fix the catalog AND delete a recording in the same change, on a path that
                // has never deleted anything — too much to do while chasing a wrong notification.
                // Stamping alone is enough: the health check asks for a row with a device copy and
                // NO Drive copy, and this row now has one.
                RecordingCatalog.markDrive(
                    applicationContext, name, alreadyThere.uri, sourceSize, deleteLocalAfter = false
                )
                continue
            }

            pending += DriveSweepOrder.Pending(
                name, runCatching { file.lastModified() }.getOrDefault(0L), SweepFile(file, sourceSize),
            )
        }

        AppLogger.i(TAG, "Sweep: ${pending.size} recording(s) to copy to Drive, newest first")
        val total = pending.size
        var done = 0
        var stoppedByUser = false
        if (total > 0) {
            DriveCopyStop.reset()
            showProgress(done, total)
        }
        val copyStart = SystemClock.elapsedRealtime()
        DriveCopyJournal.record(applicationContext, "sweep start: $total to copy, ready after ${copyStart - sweepStart}ms (Drive folder holds ${existingInDrive.size})")
        var stalls = 0
        for (entry in DriveSweepOrder.newestFirst(pending)) {
            // Between files, and also inside one: the copier call below is abandoned within about half a
            // second of the press, so "Stop for now" no longer waits out a file stuck in the provider.
            if (DriveCopyStop.isRequested()) { stoppedByUser = true; break }
            if (isStopped) break
            val name = entry.name
            val file = entry.item.file
            val sourceSize = entry.item.sizeBytes
            val mime = file.type ?: DEFAULT_MIME
            val known = existingInDrive[name.lowercase()]
            val staging = existingInDrive[CloudCopyPolicy.stagingNameFor(name).lowercase()]
            val request = DriveCopyProtocol.Request(
                src = file.uri, folder = driveFolderUri, name = name, mime = mime, sourceSize = sourceSize,
                existing = known?.uri, existingSize = known?.sizeBytes ?: -1L, staging = staging?.uri,
            )
            val fileStart = SystemClock.elapsedRealtime()
            val outcome = copier.call(
                DriveCopyProtocol.MSG_COPY, DriveCopyProtocol.copyRequest(request), CloudCopyPolicy.fileTimeoutMs(sourceSize),
            )
            val fileMs = SystemClock.elapsedRealtime() - fileStart
            if (outcome is DriveCopyClient.Outcome.Stopped) {
                DriveCopyJournal.record(applicationContext, "copy '$name' ${sourceSize}B stopped by the user after ${fileMs}ms; left for the next run")
                stoppedByUser = true
                break
            }
            val copyResult: SafHelper.CopyResult
            val stepTimes: String
            when (outcome) {
                is DriveCopyClient.Outcome.Reply -> {
                    val (r, t) = DriveCopyProtocol.readCopyReply(outcome.data)
                    copyResult = r; stepTimes = t; stalls = 0
                }
                else -> {
                    // Timed out or the copier died (Drive's process going away takes it down): leave the file
                    // for the next run and go on with the next one. A staging document it left behind is
                    // cleaned up by that next copy of the same file.
                    val why = if (outcome is DriveCopyClient.Outcome.TimedOut) "no answer within ${CloudCopyPolicy.fileTimeoutMs(sourceSize) / 1000}s" else "the Drive copier process died"
                    copyResult = SafHelper.CopyResult.Failed(why); stepTimes = ""
                    stalls++
                }
            }
            DriveCopyJournal.record(
                applicationContext,
                "copy '$name' ${sourceSize}B total=${fileMs}ms $stepTimes -> ${describe(copyResult)}",
            )
            val driveUri = when (val result = copyResult) {
                is SafHelper.CopyResult.AlreadyPresent -> result.uri
                is SafHelper.CopyResult.Copied -> result.uri.also { copied++ }
                is SafHelper.CopyResult.Failed -> {
                    failures++
                    AppLogger.w(TAG, "Failed to copy '$name' to Drive (${result.reason}).")
                    null
                }
            }.also {
                done++
                showProgress(done, total)
                DriveCopyJournal.record(
                    applicationContext,
                    "pace $done of $total, ${DriveCopyJournal.pace(done, SystemClock.elapsedRealtime() - copyStart)} files/min",
                )
            }
            if (driveUri == null) {
                if (stalls >= CloudCopyPolicy.MAX_CONSECUTIVE_STALLS) {
                    AppLogger.w(TAG, "Drive stopped answering ($stalls files in a row); ending this run, the next one retries.")
                    DriveCopyJournal.record(applicationContext, "sweep ended early: $stalls files in a row got no answer from Drive")
                    break
                }
                continue
            }

            existingInDrive[name.lowercase()] = DriveCopy(driveUri, sourceSize)
            // Never delete the file we were told is the Drive copy (see [FolderOverlap]).
            val deletedLocally = deleteLocal && !FolderOverlap.isSameDocument(file.uri, driveUri) &&
                SafHelper.deleteDocument(file, "the device copy of '$name'")
            // A failed SAF delete leaves the local recording intact, so keep the
            // catalog pointer to it. Only clear that pointer on confirmed deletion.
            RecordingCatalog.markDrive(applicationContext, name, driveUri, sourceSize,
                CloudCopyPolicy.shouldClearLocalReference(deleteLocal, deletedLocally))
        }

        if (stoppedByUser) {
            AppLogger.i(TAG, "Sweep stopped by the user after $done of $total; the rest goes on the next scheduled run (copied=$copied).")
            return Result.success()
        }
        AppLogger.i(TAG, "Sweep complete (target=$target copied=$copied failures=$failures skipped=$skipped deleteLocal=$deleteLocal).")
        // A sweep that keeps failing backs off rather than hammering the provider: the next scheduled run
        // picks the stragglers up anyway, so there is nothing to gain from an unbounded retry chain.
        return if (failures > 0 && !CloudCopyPolicy.isLastAttempt(runAttemptCount)) Result.retry() else Result.success()
    }

    /**
     * Shows or updates the "Copying recordings to Drive" notification, which also makes this a long-running
     * job: as a plain background job Android stopped it after ~10 minutes, so a big backlog never finished
     * (Honor BVL-N49, 2026-11-03). Best-effort: a phone that refuses still copies, and whatever a stopped
     * run did not reach is picked up by the next one.
     */
    private suspend fun showProgress(done: Int, total: Int) {
        runCatching {
            setForeground(
                HeavyWorkNotification.forDriveCopy(applicationContext, done, total, DriveCopyStop.pendingIntent(applicationContext))
            )
        }.onFailure { if (done == 0) AppLogger.w(TAG, "Could not show the Drive copy notification: ${it.message}") }
    }

    private fun describe(result: SafHelper.CopyResult): String = when (result) {
        is SafHelper.CopyResult.Copied -> "copied"
        is SafHelper.CopyResult.AlreadyPresent -> "already there"
        is SafHelper.CopyResult.Failed -> "FAILED (${result.reason})"
    }

    /** A recording still to upload: the device document and its byte length. */
    private data class SweepFile(val file: DocumentFile, val sizeBytes: Long)

    /** A file already present in the Drive folder: where it is, and how much of it arrived. */
    private data class DriveCopy(val uri: android.net.Uri, val sizeBytes: Long)

    companion object {
        private const val TAG = "CV:SyncSweep"
        private const val DEFAULT_MIME = "audio/ogg"

        /** Listing the Drive folder is one query; this only has to outlast a slow provider, not a stuck one. */
        private const val LIST_TIMEOUT_MS = 3 * 60_000L
    }
}
