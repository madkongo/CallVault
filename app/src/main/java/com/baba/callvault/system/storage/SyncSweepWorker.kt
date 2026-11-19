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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Batch/scheduled equivalent of [RecordingCopyWorker]. Walks the device recording folder and copies
 * every audio file that is not yet present (by display name) in the Drive folder, using the same
 * [SafHelper.copyFileToFolder] mechanism. After a successful copy it deletes the local source when the
 * storage target is [StorageTarget.DRIVE] (DRIVE = cloud only); [StorageTarget.BOTH] keeps the local copy.
 *
 * Robust by design: no-op when folders are unconfigured or the target is LOCAL; individual file errors
 * are logged and skipped; a copy failure yields [Result.retry] so WorkManager backoff re-runs the sweep.
 *
 * Every way a run can start, end or not start at all writes a line to [DriveCopyJournal] (always on): the
 * drive1 build gave up at its first step on a user's phone, 14 times, and the log could not say why.
 */
class SyncSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private fun journal(line: String) = DriveCopyJournal.record(applicationContext, line)

    override suspend fun doWork(): Result {
        val sweepStart = SystemClock.elapsedRealtime()
        // Before any copier call: a press can only come from this run's own copy notification, which is posted
        // after the listing, so anything set now is an earlier run's press (review C21).
        if (DriveCopyStop.isRequested()) journal("an earlier run's Stop for now was still set; cleared for this run")
        DriveCopyStop.reset()
        val prefs = AppPreferences(applicationContext)
        val target = prefs.getStorageTarget()
        val sourceFolderUri = prefs.getRecordingFolderUri()
        val driveFolderUri = prefs.getDriveFolderUri()
        // WorkManager only starts this once its network constraint is met, so reaching this line IS "the
        // constraints were met"; the network is read as well, because "connected" is not "validated".
        journal(
            "sweep begin: attempt ${runAttemptCount + 1}, target=$target, deleteLocal=${target == StorageTarget.DRIVE}, " +
                "constraints met (network CONNECTED required), network: ${DriveDiagnostics.network(applicationContext)}; " +
                "Drive folder ${DriveDiagnostics.uriTag(driveFolderUri)}: ${DriveDiagnostics.permission(applicationContext, driveFolderUri)}; " +
                "device folder ${DriveDiagnostics.uriTag(sourceFolderUri)}: ${DriveDiagnostics.permission(applicationContext, sourceFolderUri)}; " +
                "${DriveDiagnostics.memory(applicationContext)}",
        )
        journal("copier process, last exits: ${DriveDiagnostics.copierExits(applicationContext)}")
        return try {
            val result = run(target, sourceFolderUri, driveFolderUri, sweepStart)
            journal("sweep end: ${describe(result)} after ${SystemClock.elapsedRealtime() - sweepStart}ms")
            result
        } catch (e: CancellationException) {
            // WorkManager stopped the run (a changed schedule, lost constraint, the system's job limit).
            journal("sweep cancelled after ${SystemClock.elapsedRealtime() - sweepStart}ms: stopReason=$stopReason, attempt ${runAttemptCount + 1}")
            throw e
        } catch (t: Throwable) {
            val again = CloudCopyPolicy.shouldRetryListFailure(runAttemptCount)
            journal("sweep crashed after ${SystemClock.elapsedRealtime() - sweepStart}ms: ${t.javaClass.simpleName}: ${t.message}; ${if (again) "will retry" else "giving up until the next scheduled run"}")
            AppLogger.e(TAG, "Sweep crashed", t)
            if (again) Result.retry() else Result.success()
        }
    }

    private suspend fun run(
        target: StorageTarget,
        sourceFolderUri: android.net.Uri?,
        driveFolderUri: android.net.Uri?,
        sweepStart: Long,
    ): Result {
        if (target == StorageTarget.LOCAL) {
            AppLogger.i(TAG, "Storage target is LOCAL; nothing to sweep.")
            journal("sweep skipped: storage target is LOCAL")
            return Result.success()
        }

        if (sourceFolderUri == null || driveFolderUri == null) {
            AppLogger.w(TAG, "Sweep skipped: source or Drive folder not configured (source=$sourceFolderUri drive=$driveFolderUri).")
            journal("sweep skipped: folder not configured (device folder set=${sourceFolderUri != null}, Drive folder set=${driveFolderUri != null})")
            return Result.success()
        }
        if (FolderOverlap.overlaps(sourceFolderUri, driveFolderUri)) {
            // Copying a folder into itself (or into a folder inside it) only ever finds the recordings
            // themselves, and Drive-only would then delete them. See [FolderOverlap].
            AppLogger.e(TAG, "Sweep skipped: the Drive folder is the recording folder or overlaps it.")
            journal("sweep skipped: the Drive folder is the recording folder or overlaps it")
            return Result.success()
        }
        val sourceDir = DocumentFile.fromTreeUri(applicationContext, sourceFolderUri)
        if (sourceDir == null || !sourceDir.exists()) {
            AppLogger.w(TAG, "Source recording folder inaccessible; nothing to sweep.")
            journal("sweep skipped: the device recording folder is not accessible (document=${sourceDir != null})")
            return Result.success()
        }

        // Everything that talks to Google Drive's provider happens in the copier process (see
        // [DriveCopyService]): when Drive's process dies Android kills the process that holds a connection
        // to it, and that must not be this one. If that process cannot list the folder, the sweep falls
        // back once to doing the same work here (see [sweep]) rather than giving up for the day.
        var copier: DriveCopier = DriveCopyClient(applicationContext)
        try {
            return sweep({ copier }, { copier = it }, target, sourceDir, driveFolderUri, sweepStart)
        } finally {
            copier.close()
        }
    }

    /** Asks [copier] to list the Drive folder; returns the listing or the reason it could not. */
    private suspend fun tryList(copier: DriveCopier, driveFolderUri: android.net.Uri): Pair<DriveCopyProtocol.Listing?, String> {
        val started = SystemClock.elapsedRealtime()
        val outcome = copier.call(DriveCopyProtocol.MSG_LIST, DriveCopyProtocol.listRequest(driveFolderUri), CloudCopyPolicy.LIST_TIMEOUT_MS)
        val ms = SystemClock.elapsedRealtime() - started
        return when (outcome) {
            is DriveCopyClient.Outcome.Reply -> {
                val (listing, why) = DriveCopyProtocol.readListingWithReason(outcome.data)
                if (listing != null) {
                    val viaFile = outcome.data.getString(DriveCopyProtocol.K_LIST_FILE) != null
                    journal("list reply read: ${listing.names.size} entries from ${copier.where}${if (viaFile) " (through a file)" else ""} after ${ms}ms")
                    listing to ""
                } else null to "${copier.where} answered but gave no listing: ${why ?: "no reason in the reply"} (after ${ms}ms)"
            }
            DriveCopyClient.Outcome.Died -> null to "${copier.where} did not answer: ${(copier as? DriveCopyClient)?.lastFailure ?: "it died or failed"} (after ${ms}ms)"
            DriveCopyClient.Outcome.TimedOut -> null to "${copier.where} gave no answer within ${ms}ms"
            DriveCopyClient.Outcome.Stopped -> null to "Stop for now was pressed while listing"
        }
    }

    private suspend fun sweep(
        copierNow: () -> DriveCopier,
        useCopier: (DriveCopier) -> Unit,
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
        // A copier that could not list earlier today is not asked again today: that cost its whole time limit
        // on every retry (see [DriveListMemory]).
        val remembered = DriveListMemory.failureToday(applicationContext)
        var (listed, listFailure) = if (remembered != null) {
            journal("copier list SKIPPED: it failed earlier today ($remembered); listing and copying in the app's own process for this run")
            null to "skipped, failed earlier today ($remembered)"
        } else {
            tryList(copierNow(), driveFolderUri)
        }
        var usingFallback = false
        if (listed == null) {
            if (remembered == null) {
                if (DriveCopyStop.isRequested()) {
                    journal("sweep stopped by the user while listing: $listFailure")
                    return Result.success()
                }
                journal(
                    "list through the copier FAILED: $listFailure; copier process alive=${DriveDiagnostics.copierAlive(applicationContext)}, " +
                        "${DriveDiagnostics.memory(applicationContext)}; falling back ONCE to listing and copying in the app's own process for this run; " +
                        "the copier is not asked again today",
                )
                DriveListMemory.remember(applicationContext, listFailure)
            }
            copierNow().close()
            useCopier(LocalDriveCopier(applicationContext))
            usingFallback = true
            val second = tryList(copierNow(), driveFolderUri)
            listed = second.first
            if (listed == null) {
                val again = CloudCopyPolicy.shouldRetryListFailure(runAttemptCount)
                val wait = CloudCopyPolicy.SWEEP_BACKOFF_MINUTES * (runAttemptCount + 1)
                AppLogger.w(TAG, "Drive folder cannot be listed in either process; ${if (again) "retrying later" else "giving up until the next scheduled run"}.")
                journal(
                    "sweep not started: the Drive folder could not be listed in either process. Copier: $listFailure | App process: ${second.second}. " +
                        if (again) "Retry ${runAttemptCount + 2} of ${CloudCopyPolicy.LIST_FAILURE_MAX_ATTEMPTS} in about $wait min."
                        else "Attempt ${runAttemptCount + 1} was the last: waiting for the next scheduled time.",
                )
                return if (again) Result.retry() else Result.success()
            }
            journal("fallback list ok: ${listed.names.size} entries; this run copies in the app's own process (the Drive provider is now queried from here)")
        }
        val existingInDrive = HashMap<String, DriveCopy>(listed.names.size * 2)
        for (i in listed.names.indices) {
            existingInDrive[listed.names[i].lowercase()] = DriveCopy(listed.uris[i], listed.sizes[i])
        }
        journal("drive folder listed: ${listed.names.size} entries in ${SystemClock.elapsedRealtime() - sweepStart}ms (incl. start-up), via ${copierNow().where}")

        val deleteLocal = target == StorageTarget.DRIVE
        var copied = 0
        var failures = 0
        var skipped = 0
        var deleted = 0
        var kept = 0
        var unverified = 0
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
            // Only a length Drive reports as equal counts as "already there". A document listed with length 0
            // (a copy Drive had not settled at the end of the last run) is checked again by the copier below.
            if (alreadyThere != null && CloudCopyPolicy.isVerifiedPresent(alreadyThere.sizeBytes, sourceSize)) {
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
        if (total > 0) showProgress(done, total)
        val copyStart = SystemClock.elapsedRealtime()
        journal("sweep start: $total to copy, ready after ${copyStart - sweepStart}ms (Drive folder holds ${existingInDrive.size}), skipped $skipped empty, copying ${if (usingFallback) "in the app's own process (fallback)" else "in the :drive process"}")
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
            val outcome = copierNow().call(
                DriveCopyProtocol.MSG_COPY, DriveCopyProtocol.copyRequest(request), CloudCopyPolicy.fileTimeoutMs(sourceSize),
            )
            val fileMs = SystemClock.elapsedRealtime() - fileStart
            if (outcome is DriveCopyClient.Outcome.Stopped) {
                journal("copy '$name' ${sourceSize}B stopped by the user after ${fileMs}ms; left for the next run")
                stoppedByUser = true
                break
            }
            val copyResult: SafHelper.CopyResult
            val stepTimes: String
            var driveSize = -1L
            when (outcome) {
                is DriveCopyClient.Outcome.Reply -> {
                    val reply = DriveCopyProtocol.readCopyReply(outcome.data, name)
                    copyResult = reply.result; stepTimes = reply.steps; driveSize = reply.driveSize; stalls = 0
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
            journal("copy '$name' ${sourceSize}B total=${fileMs}ms $stepTimes -> ${describe(copyResult)}")
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
                // Every tenth file (and the last): one pace line per file would push the sweep's own start
                // and failure lines out of a capped journal on a backlog of thousands.
                if (done % PACE_EVERY == 0 || done == total) {
                    journal("pace $done of $total, ${DriveCopyJournal.pace(done, SystemClock.elapsedRealtime() - copyStart)} files/min")
                }
            }
            if (driveUri == null) {
                if (stalls >= CloudCopyPolicy.MAX_CONSECUTIVE_STALLS) {
                    AppLogger.w(TAG, "Drive stopped answering ($stalls files in a row); ending this run, the next one retries.")
                    journal("sweep ended early: $stalls files in a row got no answer from Drive")
                    break
                }
                continue
            }

            existingInDrive[name.lowercase()] = DriveCopy(driveUri, sourceSize)
            // The device copy goes only when Drive itself reports a document of the same length, and never
            // when the Drive copy IS the device file (see [FolderOverlap]). Every branch says what it did.
            var deletedLocally = false
            if (!CloudCopyPolicy.mayDeleteLocal(driveSize, sourceSize)) {
                unverified++
                journal("copy of '$name' UNVERIFIED: Drive reports $driveSize bytes, the device file is $sourceSize; the device copy is not deleted on this evidence and the next sweep checks it again")
            }
            if (deleteLocal) {
                if (FolderOverlap.isSameDocument(file.uri, driveUri)) {
                    kept++
                    journal("device copy of '$name' KEPT: the Drive copy is the same document as the device file")
                } else if (!CloudCopyPolicy.mayDeleteLocal(driveSize, sourceSize)) {
                    kept++
                    journal("device copy of '$name' KEPT: Drive reports $driveSize bytes, the device file is $sourceSize (not verified)")
                } else {
                    // A failed SAF delete leaves the local recording intact, so keep the
                    // catalog pointer to it. Only clear that pointer on confirmed deletion.
                    deletedLocally = SafHelper.deleteDocument(file, "the device copy of '$name'")
                    if (deletedLocally) deleted++ else kept++
                    journal("device copy of '$name' ${if (deletedLocally) "DELETED after Drive reported $driveSize bytes" else "KEPT: the provider refused the delete"}")
                }
            }
            RecordingCatalog.markDrive(applicationContext, name, driveUri, sourceSize,
                CloudCopyPolicy.shouldClearLocalReference(deleteLocal, deletedLocally))
        }

        if (stoppedByUser) {
            AppLogger.i(TAG, "Sweep stopped by the user after $done of $total; the rest goes on the next scheduled run (copied=$copied).")
            journal("sweep stopped by the user after $done of $total (copied=$copied failures=$failures deleted=$deleted kept=$kept)")
            return Result.success()
        }
        if (isStopped) {
            journal("sweep interrupted: WorkManager asked it to stop after $done of $total (stopReason=$stopReason)")
        }
        AppLogger.i(TAG, "Sweep complete (target=$target copied=$copied failures=$failures skipped=$skipped deleteLocal=$deleteLocal).")
        journal("sweep complete: copied=$copied failures=$failures skipped=$skipped deleteLocal=$deleteLocal deleted=$deleted kept=$kept unverified=$unverified")
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
        }.onFailure {
            if (done == 0) {
                AppLogger.w(TAG, "Could not show the Drive copy notification: ${it.message}")
                journal("copy notification could not be shown: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun describe(result: SafHelper.CopyResult): String = when (result) {
        is SafHelper.CopyResult.Copied -> "copied"
        is SafHelper.CopyResult.AlreadyPresent -> "already there"
        is SafHelper.CopyResult.Failed -> "FAILED (${result.reason})"
    }

    private fun describe(result: Result): String = when (result) {
        is Result.Success -> "result=success"
        is Result.Retry -> "result=retry (attempt ${runAttemptCount + 1})"
        else -> "result=failure"
    }

    /** A recording still to upload: the device document and its byte length. */
    private data class SweepFile(val file: DocumentFile, val sizeBytes: Long)

    /** A file already present in the Drive folder: where it is, and how much of it arrived. */
    private data class DriveCopy(val uri: android.net.Uri, val sizeBytes: Long)

    companion object {
        private const val TAG = "CV:SyncSweep"
        private const val DEFAULT_MIME = "audio/ogg"
        private const val PACE_EVERY = 10
    }
}
