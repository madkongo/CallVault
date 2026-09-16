/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.system.storage.RetentionPolicy
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recordings sitting in the storage folders that the catalog has no entry for.
 *
 * **Why they exist, and why retention has to see them.** The catalog is the app's whole view of the
 * world — the Home list reads it and [com.baba.callvault.system.storage.RetentionSweepWorker] used to
 * walk nothing else. A file the catalog has forgotten was therefore invisible AND immortal: no period
 * applied to it however old it got. Measured on the maintainer's device on 2026-08-04 with retention set
 * to 7 days, the app showed a correct-looking 64 recordings going back exactly 7 days while 8 files on
 * the device and 123 on Drive had outlived the window, the oldest by 48 days.
 *
 * A delete that forgot the entry even when the file survived put them there (fixed in
 * [RecordingsRepository.deleteFile]), but that is only the cause we happened to find. A storage folder is
 * shared with the user and the OS; a catalog can fall behind reality for reasons the app will never
 * author. Retention is a promise about the folder, not about our bookkeeping, so the sweep reads both.
 */
object UntrackedRecordings {

    private const val TAG = "CV:Untracked"

    /** Untracked copies, split by the folder they were found in — each has its own retention period. */
    data class Found(
        val device: List<RecordingItem>,
        val drive: List<RecordingItem>,
    ) {
        val total: Int get() = device.size + drive.size
    }

    /**
     * Enumerates the configured folders and returns every copy the catalog is missing. Read-only;
     * deletes nothing. Call OFF the main thread; never throws.
     */
    suspend fun find(context: Context): Found = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = AppPreferences(context)
            val catalogued = RecordingCatalog.all(context)
            val knownDevice = catalogued.mapNotNull { row -> row.displayName.takeIf { row.localUri != null } }.toSet()
            val knownDrive = catalogued.mapNotNull { row -> row.displayName.takeIf { row.driveUri != null } }.toSet()

            val device = untrackedIn(context, prefs.getRecordingFolderUri(), knownDevice)
            val drive = untrackedIn(context, prefs.getDriveFolderUri(), knownDrive)
            if (device.isNotEmpty() || drive.isNotEmpty()) {
                AppLogger.i(TAG, "Untracked: ${device.size} on the device, ${drive.size} on Drive")
            }
            Found(device = device, drive = drive)
        }.getOrElse {
            AppLogger.w(TAG, "Could not enumerate untracked recordings: ${it.message}")
            Found(emptyList(), emptyList())
        }
    }

    /**
     * The untracked files of one folder — and ONLY those whose name CallVault itself would have written.
     *
     * A storage folder can be any folder the user picked, up to and including one they keep other audio
     * in. Deleting a stranger's file because it is old would be a far worse bug than the one this fixes,
     * so the filename template is the gate: [RecordingItem.startedAtMillis] is parsed out of the
     * timestamp CallVault puts at the front of every name, and anything that does not carry one is left
     * alone. Catalogued recordings are exempt from this check — those we already know are ours.
     *
     * A file the user imported passes that test — we wrote its name — and [RetentionPolicy.isEligible]
     * turns it down for the other reason: it is the user's own audio, not a call, and no retention period
     * they set for their calls was ever an instruction to destroy it.
     */
    private fun untrackedIn(context: Context, folder: android.net.Uri?, known: Set<String>): List<RecordingItem> {
        if (folder == null) return emptyList()
        return RecordingsRepository.enumerateFolder(context, folder)
            .filter {
                it.displayName !in known &&
                    RetentionPolicy.isEligible(it.displayName, it.startedAtMillis)
            }
    }
}
