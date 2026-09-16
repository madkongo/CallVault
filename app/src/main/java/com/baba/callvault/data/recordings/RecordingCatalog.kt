/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import android.net.Uri
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.data.transcripts.TranscriptCascade
import com.baba.callvault.utils.AppLogger

/**
 * App-facing facade over the recordings catalog ([RecordingEntry] / Room). This is CallVault's source
 * of truth for which recordings exist and where each copy lives, so the Home list never has to query a
 * cloud provider's (Google Drive's) eventually-consistent folder listing.
 *
 * Lifecycle of a row:
 *  1. [recordLocal] — written when a recording finishes (the file is on the device).
 *  2. [markDrive]   — the post-call copy ([com.baba.callvault.system.storage.RecordingCopyWorker]) or the
 *                     scheduled sweep ([com.baba.callvault.system.storage.SyncSweepWorker]) stamps the
 *                     Drive copy onto the same row by name (clearing the local copy for DRIVE-only mode).
 *  3. [removeName] / [removeCopyByUri] — when the user deletes a recording (all copies, or one copy).
 *
 * For pre-existing recordings made before the catalog existed, [importIfEmpty] seeds the table once from
 * a folder scan. All operations are best-effort and never throw; on a DB failure they log and move on.
 */
object RecordingCatalog {

    private const val TAG = "CV:RecordingCatalog"

    private fun dao(context: Context) = RecordingDatabase.get(context).recordingDao()

    /**
     * Records (or refreshes) the device-folder copy of a finished recording, preserving any Drive copy
     * already stamped on the same-named row. Called at record-finish for every storage target.
     */
    suspend fun recordLocal(context: Context, displayName: String, localUri: Uri, sizeBytes: Long, lastModified: Long) {
        runCatching {
            val dao = dao(context)
            val existing = dao.findByName(displayName)
            val merged = (existing ?: RecordingEntry(displayName)).copy(
                localUri = localUri.toString(),
                localSizeBytes = sizeBytes.takeIf { it > 0L },
                lastModified = lastModified.takeIf { it > 0L } ?: existing?.lastModified ?: 0L
            )
            dao.upsert(merged)
        }.onFailure { AppLogger.w(TAG, "recordLocal('$displayName') failed: ${it.message}") }
    }

    /**
     * Stamps the Drive copy onto the recording named [displayName] (creating a Drive-only row if the
     * recording is not yet catalogued). When [deleteLocalAfter] is true (DRIVE-only mode), the local
     * copy reference is cleared because the worker deletes the on-device file after copying.
     *
     * [lastModified] is only consulted when a Drive-ONLY row has to be created, where there is no device
     * copy to have supplied one. It matters because a row with no timestamp is one the retention sweep
     * refuses to age out (it will not delete a recording whose age it cannot establish), so a Drive-only
     * row created without it would never expire. The sync path leaves it at 0 as before: it always stamps
     * Drive onto a row [recordLocal] has already dated.
     */
    suspend fun markDrive(
        context: Context,
        displayName: String,
        driveUri: Uri,
        driveSizeBytes: Long?,
        deleteLocalAfter: Boolean,
        lastModified: Long = 0L,
    ) {
        runCatching {
            val dao = dao(context)
            val size = driveSizeBytes?.takeIf { it > 0L }
            if (dao.findByName(displayName) == null) {
                dao.upsert(
                    RecordingEntry(
                        displayName = displayName,
                        driveUri = driveUri.toString(),
                        driveSizeBytes = size,
                        lastModified = lastModified.takeIf { it > 0L } ?: 0L,
                    )
                )
            } else {
                dao.setDrive(displayName, driveUri.toString(), size)
                if (deleteLocalAfter) dao.clearLocal(displayName)
            }
        }.onFailure { AppLogger.w(TAG, "markDrive('$displayName') failed: ${it.message}") }
    }

    /**
     * Remembers [durationSeconds] for [displayName] so the file never has to be opened for it again.
     *
     * Only ever called with a positive value: a failed read stays uncached so a transient failure
     * cannot become a permanent wrong answer, which is the behaviour before this cache existed.
     */
    suspend fun setDuration(context: Context, displayName: String, durationSeconds: Long) {
        runCatching { dao(context).setDuration(displayName, durationSeconds) }
            .onFailure { AppLogger.w(TAG, "setDuration('$displayName') failed: ${it.message}") }
    }

    /** All catalogued recordings, newest-first. Never throws (returns empty on failure). */
    suspend fun all(context: Context): List<RecordingEntry> =
        runCatching { dao(context).getAll() }.getOrElse {
            AppLogger.w(TAG, "all() failed: ${it.message}"); emptyList()
        }

    /**
     * Removes the recording (all copies) from the catalog, and with it any transcript.
     *
     * The transcript cascade is explicit because transcripts live in a separate database (see
     * [TranscriptCascade]); SQLite cannot do it for us.
     */
    suspend fun removeName(context: Context, displayName: String) {
        runCatching { dao(context).deleteByName(displayName) }
            .onFailure { AppLogger.w(TAG, "removeName('$displayName') failed: ${it.message}") }
        TranscriptCascade.deleteFor(context, listOf(displayName))
    }

    /**
     * Drops the catalog row for [displayName] and **nothing else** — no transcript, no summary, no
     * note, no tag.
     *
     * The one caller is [TranscribeOnlyAudio], where the whole point is that the text survives the
     * audio: the user asked for a file to be transcribed and not kept, so the words are the thing
     * that must still be there afterwards. [removeName] would delete them, which is precisely the
     * cascade it exists to run.
     *
     * **Never use this for a user's delete.** A recording removed this way leaves a searchable
     * transcript of a private call behind it, which is more exposing than the audio it replaced —
     * the defect [TranscriptCascade] was written to prevent.
     */
    suspend fun forgetName(context: Context, displayName: String) {
        runCatching { dao(context).deleteByName(displayName) }
            .onFailure { AppLogger.w(TAG, "forgetName('$displayName') failed: ${it.message}") }
    }

    /**
     * Clears the single copy whose content URI is [uri] (device or Drive) from its row, then drops the
     * row if no copy remains. Mirrors a per-copy delete of a BOTH recording.
     */
    suspend fun removeCopyByUri(context: Context, uri: Uri) {
        // Names whose last copy this call removes. Captured before deleteEmpty(), which is a bulk
        // delete and so cannot report what it dropped — and the transcript cascade needs to know.
        // This is the path the retention sweep takes (it deletes per copy, not by name).
        var orphaned = emptyList<String>()

        runCatching {
            val dao = dao(context)
            val s = uri.toString()
            val row = dao.getAll().firstOrNull { it.localUri == s || it.driveUri == s } ?: return@runCatching
            if (row.localUri == s) dao.clearLocal(row.displayName)
            if (row.driveUri == s) dao.clearDrive(row.displayName)
            orphaned = dao.getAll()
                .filter { it.localUri == null && it.driveUri == null }
                .map { it.displayName }
            dao.deleteEmpty()
        }.onFailure { AppLogger.w(TAG, "removeCopyByUri($uri) failed: ${it.message}") }

        TranscriptCascade.deleteFor(context, orphaned)
    }

    /**
     * One-time seed: if the catalog is empty, populate it from [scan] (a folder enumeration). Used so
     * recordings made before the catalog existed still appear. No-op once any row exists.
     */
    suspend fun importIfEmpty(context: Context, scan: suspend () -> List<RecordingEntry>) {
        runCatching {
            val dao = dao(context)
            if (dao.count() > 0) return
            val seeded = scan()
            if (seeded.isNotEmpty()) {
                dao.upsertAll(seeded)
                AppLogger.i(TAG, "Seeded recordings catalog with ${seeded.size} pre-existing file(s).")
            }
        }.onFailure { AppLogger.w(TAG, "importIfEmpty failed: ${it.message}") }
    }
}
