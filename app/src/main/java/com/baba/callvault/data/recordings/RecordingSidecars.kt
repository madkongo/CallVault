/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.system.interop.TranscriptSidecar
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes the sidecars CallVault writes beside a recording (the `.md` transcript/notes and the `.json` details) from
 * the device folder and the Drive folder, the way [RecordingsRepository.deleteRecording] does for an ordinary delete.
 *
 * For the recordings whose audio goes by another path: a part retired by a merge, and the hidden parts of a merged
 * call deleted for good (owner decision N2). One listing per folder for the whole set. Every failure is logged;
 * never throws.
 */
object RecordingSidecars {

    private const val TAG = "CV:Sidecars"

    /** Deletes the sidecars of [audioNames] in both folders; returns how many were deleted. */
    suspend fun deleteFor(context: Context, audioNames: Collection<String>): Int = withContext(Dispatchers.IO) {
        if (audioNames.isEmpty()) return@withContext 0
        val wanted = audioNames.flatMapTo(HashSet()) {
            listOf(TranscriptSidecar.sidecarNameFor(it), MetadataSidecar.sidecarNameFor(it))
        }
        val prefs = AppPreferences(context)
        var deleted = 0
        for (folder in listOfNotNull(prefs.getRecordingFolderUri(), prefs.getDriveFolderUri())) {
            runCatching {
                val tree = DocumentFile.fromTreeUri(context, folder) ?: run {
                    AppLogger.w(TAG, "Could not open $folder to delete ${wanted.size} sidecar(s)")
                    return@runCatching
                }
                for (doc in tree.listFiles()) {
                    val name = doc.name ?: continue
                    if (!doc.isFile || name !in wanted) continue
                    val gone = runCatching { doc.delete() }.getOrElse { e ->
                        AppLogger.w(TAG, "Deleting sidecar '$name' threw: ${e.message}")
                        false
                    }
                    if (gone) deleted++ else AppLogger.w(TAG, "Sidecar '$name' in $folder was not deleted; it stays")
                }
            }.onFailure { AppLogger.w(TAG, "Could not list $folder to delete sidecars: ${it.message}") }
        }
        deleted
    }
}
