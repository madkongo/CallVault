/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.DriveCatalogRepair
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.UntrackedRecordings
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.transcripts.TranscriptCascade
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.data.transcripts.FavouriteRepository

/**
 * Daily sweep that permanently deletes recordings older than the configured retention period. Reads the
 * Room catalog (the source of truth for the Home list) and applies retention PER COPY: device copies use
 * [AppPreferences.getRetentionLocalDays], Drive copies use [AppPreferences.getRetentionDriveDays] (0 =
 * keep forever). Age is measured from each entry's recorded timestamp ([lastModified]).
 *
 * Deletion goes through [RecordingsRepository.deleteFile], which removes the SAF file and clears that copy
 * from the catalog ONLY once the file is actually gone (dropping the row when no copy remains). Robust by
 * design: entries with an unknown age are never deleted; a failed delete (e.g. Drive offline) keeps its
 * catalog entry and is retried on the next daily run.
 *
 * **It also sweeps what the catalog has lost.** Walking the catalog alone made a bookkeeping gap into a
 * permanent exemption: a file we had no entry for was never considered, so it sat in the folder for ever
 * regardless of its age, and one device had accumulated 131 of them. The setting promises a daily check of
 * the recording folders, so the folders are what gets checked — see [UntrackedRecordings], which also
 * explains why only names CallVault itself writes are eligible.
 */
class RetentionSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = AppPreferences(applicationContext)
        // Before any pass trusts a Drive size: sizes stamped before review C7 were the device's own. Until that clear
        // has succeeded, no device copy is deleted at all (age or cap): the old sizes would vouch for them.
        val sizesTrusted = RecordingCatalog.forgetUnconfirmedDriveSizesOnce(applicationContext)
        if (!sizesTrusted) {
            AppLogger.w(TAG, "Old Drive sizes could not be cleared yet; deleting no device copy this run")
        }
        val localDays = prefs.getRetentionLocalDays()
        val driveDays = prefs.getRetentionDriveDays()
        val capBytes = prefs.getStorageCapBytes()
        // The size cap is a second, independent reason to sweep. Testing only the day counts here is
        // what would have made a cap-only setup do nothing at all, for ever, while showing as on.
        if (localDays <= 0 && driveDays <= 0 && capBytes <= 0L) {
            AppLogger.i(TAG, "Retention off and no size cap; nothing to sweep.")
            SilentFailureNotifier.updateRetentionWaitingForDrive(applicationContext, 0)
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val localCutoff = RetentionPolicy.cutoffFor(localDays, now)
        val driveCutoff = RetentionPolicy.cutoffFor(driveDays, now)

        // A Drive reference minted under a grant we no longer hold can be neither used nor noticed —
        // the catalog pass fails on it for ever while the untracked pass skips the file as already
        // known. Repair those first, so both halves below see a catalog that matches reality.
        DriveCatalogRepair.reconcile(applicationContext)
        DriveCatalogRepair.forgetDeletedIfDue(applicationContext)

        var deletedLocal = 0
        var deletedDrive = 0
        // Age here is the catalog's `lastModified`, and nothing about an entry says how it got there.
        // So whatever catalogues an import MUST stamp it with the time it was imported, never with the
        // date the audio was originally recorded: a two-year-old voice note brought in to transcribe
        // would arrive already expired and be deleted by this pass the same night.
        // Starred recordings are never deleted by age either. A failed read must protect everything, as for the
        // size cap below: the age pass sits this run out rather than delete what the user starred to keep.
        val starred = runCatching { FavouriteRepository.snapshot(applicationContext).toSet() }.getOrElse {
            AppLogger.w(TAG, "Could not read the starred recordings (${it.message}); skipping retention this run")
            return Result.success()
        }
        val target = prefs.getStorageTarget()
        val template = prefs.getFileNameTemplate()
        val mergedNames = mergedNames() ?: run {
            AppLogger.w(TAG, "Could not read which recordings are merges; skipping retention this run")
            return Result.success()
        }
        // Names kept only because Drive holds no proven copy, for the notice (N3); a set, as age and cap overlap.
        val keptForDrive = HashSet<String>()
        var keptUnproven = 0
        var keptForeign = 0
        for (entry in RecordingCatalog.all(applicationContext)) {
            // And the right stamp is only half of it. Once the retention period elapses this pass
            // would delete an import like anything else — except that for a call the Drive copy
            // survives and the row keeps its transcript, while an import has no Drive copy by design.
            // The same delete would therefore destroy the only copy of something the user handed us.
            if (!RetentionPolicy.mayAgeOut(entry.displayName, starred)) continue
            // Catalogued is not authored: the rescan lists other apps' audio too (review C10).
            if (!RetentionPolicy.isOursToSweep(entry.displayName, template, mergedNames)) {
                keptForeign++
                continue
            }
            val ts = entry.lastModified
            val localUri = entry.localUri
            if (localUri != null && sizesTrusted && RetentionPolicy.isExpired(ts, localCutoff)) {
                // In Both/Drive mode the device copy is the only one until Drive holds it at the same size (C7).
                if (!RetentionPolicy.deviceCopyMayGo(target, entry)) {
                    keptUnproven++
                    keptForDrive += entry.displayName
                }
                else if (RecordingsRepository.deleteFile(applicationContext, localUri.toUri())) deletedLocal++
            }
            val driveUri = entry.driveUri
            if (driveUri != null && RetentionPolicy.isExpired(ts, driveCutoff)) {
                if (RecordingsRepository.deleteFile(applicationContext, driveUri.toUri())) deletedDrive++
            }
        }

        if (keptForeign > 0) {
            AppLogger.i(TAG, "Kept $keptForeign catalogued file(s) whose name is not one CallVault writes")
        }
        if (keptUnproven > 0) {
            AppLogger.i(TAG, "Kept $keptUnproven expired device copies whose Drive copy is not proven yet; retried next run")
        }

        // The folders, not just our record of them. A recording the catalog has lost is exactly as old as
        // one it remembers, and the setting promises a daily check of the folder — so a bookkeeping gap
        // must not quietly become an exemption that lasts forever.
        val untracked = UntrackedRecordings.find(applicationContext)
        // An untracked device file has no catalogued Drive copy, so in Both/Drive mode nothing proves it reached
        // Drive: it may be the only copy, and it stays (review C7). Device-only mode is unchanged.
        val untrackedDevice = untracked.device.filter { it.displayName !in starred }
        val deletedDeviceNames = if (target == StorageTarget.LOCAL) {
            deleteExpired(untrackedDevice, localCutoff, "device")
        } else {
            val keptFiles = untrackedDevice.filter { RetentionPolicy.isExpired(it.lastModified, localCutoff) }
            keptForDrive += keptFiles.map { it.displayName }
            val kept = keptFiles.size
            if (kept > 0) {
                AppLogger.i(TAG, "Kept $kept expired untracked device file(s): no proven Drive copy in $target mode")
            }
            emptyList()
        }
        val deletedDriveNames = deleteExpired(untracked.drive.filter { it.displayName !in starred }, driveCutoff, "Drive")
        deletedLocal += deletedDeviceNames.size
        deletedDrive += deletedDriveNames.size
        cascadeForUntracked(untracked, deletedDeviceNames + deletedDriveNames)

        // After the age pass, never before: an expired recording should go because it expired, and
        // counting it against the cap first could evict a newer one that the age rule was going to
        // spare. Running second also means the cap only ever sees what age retention chose to keep.
        val deletedByCap = if (capBytes > 0L && sizesTrusted) sweepStorageCap(capBytes, keptForDrive)
        else if (capBytes > 0L) null else 0
        val awaitingReconfirm = target != StorageTarget.LOCAL &&
            !RecordingCatalog.areDriveSizesReconfirmed(applicationContext)
        if (awaitingReconfirm) {
            // Sizes were cleared and no Drive sweep has stamped them again yet: everything expired would count as
            // "kept for Drive". Left alone until one has (review C7/N3).
            AppLogger.i(TAG, "No Drive sweep has re-confirmed sizes since they were cleared; the 'kept for Drive' notice waits")
        } else if (!sizesTrusted || deletedByCap == null || !untracked.complete) {
            // A pass did not look, so the count is partial: clearing or lowering the notice now would be a false
            // all-clear. It is left as it was until a run that finishes (N3).
            val which = when {
                !sizesTrusted -> "device-copy age pass"
                deletedByCap == null -> "size cap"
                else -> "untracked-file pass"
            }
            AppLogger.w(TAG, "The $which did not finish this run; the 'kept for Drive' notice is left as it was")
        } else {
            SilentFailureNotifier.updateRetentionWaitingForDrive(applicationContext, keptForDrive.size)
        }

        AppLogger.i(
            TAG,
            "Retention sweep complete (deletedLocal=$deletedLocal deletedDrive=$deletedDrive deletedByCap=$deletedByCap)."
        )
        return Result.success()
    }

    /**
     * Deletes the oldest device copies until the on-device library is back under [capBytes].
     *
     * Device copies only, and only the device copy: a recording that is also in Drive keeps its row,
     * its transcript and its place in the list — the cap frees this phone, it does not destroy the
     * recording. The transcript cascade therefore runs only for the ones whose last copy this takes,
     * which `RecordingCatalog.removeCopyByUri` already handles.
     *
     * @return how many device copies were deleted.
     */
    /** @return how many device copies were deleted, or null when the cap sat this run out (see the log). */
    private suspend fun sweepStorageCap(capBytes: Long, keptForDrive: MutableSet<String>): Int? {
        // A failed read here must mean "protect everything", not "protect nothing". Sweeping with an
        // empty exemption set would delete precisely the recordings the user starred to keep, so the
        // cap sits this run out instead and tries again tomorrow.
        val favourites = runCatching { FavouriteRepository.snapshot(applicationContext).toSet() }
            .getOrElse {
                AppLogger.w(
                    TAG,
                    "Could not read the starred recordings (${it.message}); skipping the size cap this run " +
                        "rather than sweeping without the exemption."
                )
                return null
            }

        val prefs = AppPreferences(applicationContext)
        val target = prefs.getStorageTarget()
        val template = prefs.getFileNameTemplate()
        val mergedNames = mergedNames() ?: run {
            AppLogger.w(TAG, "Could not read which recordings are merges; skipping the size cap this run")
            return null
        }
        val entries = RecordingCatalog.all(applicationContext)
        val uriByName = entries.mapNotNull { e -> e.localUri?.let { e.displayName to it } }.toMap()
        val candidates = entries.mapNotNull { entry ->
            // No device copy, or a size we never measured: not something this cap can act on. A
            // recording is never deleted on the strength of a size we do not have.
            if (entry.localUri == null) return@mapNotNull null
            val size = entry.localSizeBytes ?: return@mapNotNull null
            StorageCapPolicy.Candidate(
                displayName = entry.displayName,
                sizeBytes = size,
                lastModified = entry.lastModified,
                isFavourite = entry.displayName in favourites,
                // For a call the cap deletes the device copy and the Drive copy survives. An import
                // has no Drive copy by design, so the same delete would destroy the only copy of
                // something the user handed us, plus its transcript.
                isImported = ImportedRecording.isImported(entry.displayName),
                // Counted toward the total, never evicted: until Drive holds it, this is the only copy (C7).
                isOnlyCopy = !RetentionPolicy.deviceCopyMayGo(target, entry),
                // Counted, never evicted: catalogued is not authored (review C10).
                isForeign = !RetentionPolicy.isOursToSweep(entry.displayName, template, mergedNames),
            )
        }

        keptForDrive += RetentionDriveNotice.keptByCap(candidates, capBytes)
        val doomed = StorageCapPolicy.selectForEviction(candidates, capBytes)
        if (doomed.isEmpty()) return 0

        var deleted = 0
        for (name in doomed) {
            val uri = uriByName[name] ?: continue
            if (RecordingsRepository.deleteFile(applicationContext, uri.toUri())) deleted++
        }

        // Said plainly, because it is the one case where the setting visibly does not do what it
        // says: the library stays over the cap and that is the intended answer, not a failure.
        val protectedBytes = candidates.filter { it.isFavourite || it.isImported || it.isOnlyCopy || it.isForeign }.sumOf { it.sizeBytes }
        if (protectedBytes > capBytes) {
            val unproven = candidates.count { it.isOnlyCopy }
            val foreign = candidates.count { it.isForeign }
            AppLogger.i(
                TAG,
                "Starred, imported, not-yet-in-Drive ($unproven) and not-CallVault ($foreign) files alone " +
                    "($protectedBytes bytes) exceed the ${capBytes}-byte cap; staying over it rather than deleting them."
            )
        }
        AppLogger.i(TAG, "Storage cap: deleted $deleted of ${doomed.size} selected device copies.")
        return deleted
    }

    /**
     * Deletes the untracked copies that are past [cutoff], straight through SAF.
     *
     * No catalog bookkeeping, because there is no entry to keep — which also makes this the one part of
     * the sweep that retries itself for free: the folder is re-enumerated every run, so a copy that fails
     * to delete today is simply found again tomorrow.
     */
    private fun deleteExpired(items: List<RecordingItem>, cutoff: Long?, where: String): List<RecordingItem> {
        val deleted = mutableListOf<RecordingItem>()
        for (item in items) {
            if (!RetentionPolicy.isExpired(item.lastModified, cutoff)) continue
            val gone = runCatching {
                DocumentFile.fromSingleUri(applicationContext, item.uri)?.delete() == true
            }.getOrDefault(false)
            if (gone) deleted += item
            else AppLogger.w(TAG, "Could not delete untracked $where copy '${item.displayName}'")
        }
        if (deleted.isNotEmpty()) {
            AppLogger.i(TAG, "Deleted ${deleted.size} untracked $where recording(s) past the retention period")
        }
        return deleted
    }

    /**
     * Takes the transcript, note and summary of every untracked recording this sweep deleted the LAST copy
     * of — the cascade the catalogued half gets for free from [RecordingCatalog], and this half never had.
     *
     * Only names nothing is left of: an untracked copy that survived in the other folder, or a catalog row
     * that still holds a copy, means the recording still exists and its transcript must stay. See
     * [UntrackedCascade] for why that distinction is the whole point.
     */
    private suspend fun cascadeForUntracked(untracked: UntrackedRecordings.Found, deleted: List<RecordingItem>) {
        if (deleted.isEmpty()) return
        // By URI, not by name: a name with a copy in each folder appears twice, and only the copy that was
        // actually deleted may be crossed off. Comparing names here would call a surviving copy deleted.
        val deletedUris = deleted.map { it.uri }.toSet()
        val survivingUntracked = (untracked.device + untracked.drive)
            .filterNot { it.uri in deletedUris }
            .map { it.displayName }
        val cataloguedWithCopies = RecordingCatalog.all(applicationContext)
            .filter { it.localUri != null || it.driveUri != null }
            .map { it.displayName }

        val orphaned = UntrackedCascade.orphanedNames(
            deleted = deleted.map { it.displayName },
            survivingUntracked = survivingUntracked,
            cataloguedWithCopies = cataloguedWithCopies,
        )
        if (orphaned.isEmpty()) return
        AppLogger.i(TAG, "Removing what ${orphaned.size} deleted untracked recording(s) left behind")
        TranscriptCascade.deleteFor(applicationContext, orphaned)
    }

    /** Names of merge outputs, or null when they cannot be read (the caller then deletes nothing). */
    private suspend fun mergedNames(): Set<String>? = runCatching {
        RecordingDatabase.get(applicationContext).mergePartDao().allMergedNames().toSet()
    }.getOrElse {
        AppLogger.w(TAG, "Reading the merge outputs failed: ${it.message}")
        null
    }

    companion object {
        private const val TAG = "CV:RetentionSweep"
    }
}
