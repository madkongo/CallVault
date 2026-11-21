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
import com.baba.callvault.system.storage.SafHelper
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

    /**
     * Every name a delete of [audioNames] removes from a folder: the audio, the `.md` transcript/notes sidecar and the
     * `.json` details file beside it (C41: the bulk delete used to leave the `.json`, which holds the number and the
     * contact name). Pure.
     */
    internal fun namesToDelete(audioNames: Collection<String>): Set<String> =
        audioNames.flatMapTo(HashSet()) {
            listOf(it, TranscriptSidecar.sidecarNameFor(it), MetadataSidecar.sidecarNameFor(it))
        }

    /**
     * The sidecars in one folder, given its [folderNames], that go with [orphanedAudio] (recordings whose last copy
     * was just deleted). Pure.
     *
     * A sidecar is named by its audio's base name, so two audio files of one base (`call.ogg`, `call.m4a`) share it.
     * It is therefore planned only when NO audio file with that base is left in this folder: a copy that survived
     * (a failed delete, a file the catalog never knew, a Drive listing still showing it) keeps its text. Only
     * sidecar names derived from [orphanedAudio] are ever returned, so nothing else in a shared folder is touched.
     */
    internal fun orphanedSidecarsIn(folderNames: Collection<String>, orphanedAudio: Collection<String>): Set<String> {
        if (orphanedAudio.isEmpty()) return emptySet()
        val audioBases = folderNames.filter { RecordingsRepository.isAudioName(it) }
            .mapTo(HashSet()) { it.substringBeforeLast('.', it) }
        val present = folderNames.toHashSet()
        return orphanedAudio
            .filter { it.substringBeforeLast('.', it) !in audioBases }
            .flatMap { listOf(TranscriptSidecar.sidecarNameFor(it), MetadataSidecar.sidecarNameFor(it)) }
            .filterTo(HashSet()) { it in present && !RecordingsRepository.isAudioName(it) }
    }

    /**
     * Deletes the sidecars of recordings whose LAST copy has just been deleted (C41), from the device and Drive folders.
     * One listing per folder for the whole set, and a sidecar goes only when no audio of its base is left in that
     * folder ([orphanedSidecarsIn]). A folder that cannot be listed is skipped and logged; the sidecars stay. Never throws.
     */
    suspend fun deleteOrphanedFor(context: Context, orphanedAudio: Collection<String>): Int = withContext(Dispatchers.IO) {
        if (orphanedAudio.isEmpty()) return@withContext 0
        val prefs = AppPreferences(context)
        var deleted = 0
        for (folder in listOfNotNull(prefs.getRecordingFolderUri(), prefs.getDriveFolderUri())) {
            runCatching {
                val listing = SafHelper.listChildren(context, folder) ?: run {
                    AppLogger.w(TAG, "Could not list a storage folder; the sidecars of ${orphanedAudio.size} deleted recording(s) stay there")
                    return@runCatching
                }
                val doomed = orphanedSidecarsIn(listing.names, orphanedAudio)
                listing.names.forEachIndexed { i, name ->
                    if (name !in doomed) return@forEachIndexed
                    val gone = runCatching {
                        val doc = DocumentFile.fromSingleUri(context, listing.uris[i])
                        // Only a plain file: a folder that happens to carry a sidecar's name is never touched.
                        if (doc == null || !doc.isFile) {
                            AppLogger.w(TAG, "'$name' is not a plain file; left alone")
                            return@runCatching false
                        }
                        doc.delete()
                    }.getOrElse { e -> AppLogger.w(TAG, "Deleting sidecar '$name' threw: ${e.message}"); false }
                    if (gone) deleted++ else AppLogger.w(TAG, "Sidecar '$name' was not deleted; it stays")
                }
            }.onFailure { AppLogger.w(TAG, "Could not clean up sidecars in a storage folder: ${it.message}") }
        }
        if (deleted > 0) AppLogger.i(TAG, "Deleted $deleted sidecar(s) of ${orphanedAudio.size} recording(s) whose last copy went")
        deleted
    }
}
