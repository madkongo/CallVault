/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.RecordingSidecars
import com.baba.callvault.data.recordings.db.MergePartEntry
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.StorageRouter
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.io.FileInputStream

/**
 * Turns several calls into one recording, and takes one back apart.
 *
 * The audio work lives in [AudioConcat] and [AudioSplit]; this is the part that decides what happens
 * to files on disk and rows in the catalog, and it exists mostly to get one ordering right.
 *
 * 🚨 **Verify, then delete. Never the other way round.** A merge writes to app-private staging first,
 * re-opens that staged file and counts its frames against what was put in, and only deletes the
 * original calls once that agrees. The staged file is complete and checked before anything the user
 * owns is touched, so a muxer that silently truncated cannot cost a conversation. If any step before
 * the delete fails, nothing has been lost and the merge simply reports why.
 *
 * Deleting the originals is safe because the merged file *contains* them: the join copies encoded
 * frames without re-encoding, so [unMerge] cuts them back out exactly. That is proven by
 * `MergeRoundTripTest`, and if that test ever fails this class must stop deleting.
 */
/** The visible stages of a merge, surfaced to the progress dialog. Download/clean-up only appear
 *  when a Drive-only part had to be pulled down first; a plain local merge goes straight to MERGING. */
enum class MergePhase { DOWNLOADING, MERGING, DELETING_TEMP, COMPLETED }

object MergeService {

    private const val TAG = "CV:Merge"

    sealed interface Outcome {
        /** [partCount] is the flattened count — chaining a merged call contributes all of its parts. */
        data class Merged(val mergedName: String, val partCount: Int) : Outcome

        /** The calls cannot be joined as they are, with a reason fit to show someone. */
        data class Refused(val reason: String) : Outcome

        /** [restored] calls are back, and the merged recording is gone. */
        data class UnMerged(val restored: Int) : Outcome

        /** Something went wrong; nothing was deleted. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Joins [primaryName] and [thenNames] into one recording, in exactly that order.
     *
     * The order is the user's — the order they ticked the calls — and is not sorted here. The merged
     * recording inherits the primary's identity, so it keeps its date, direction and number.
     */
    suspend fun merge(
        context: Context,
        primaryName: String,
        thenNames: List<String>,
        /** Called with each call's index as it is joined, for the progress dialog. */
        onPartProgress: (Int) -> Unit = {},
        /** Called as the merge moves between phases (download → merge → clean up), for the progress dialog. */
        onPhase: (MergePhase) -> Unit = {},
    ): Outcome {
        if (thenNames.isEmpty()) return Outcome.Refused("A merge needs at least two calls")

        val names = listOf(primaryName) + thenNames
        if (names.size != names.toSet().size) return Outcome.Refused("The same call was chosen twice")

        val db = RecordingDatabase.get(context)
        val entries = names.map { name ->
            db.recordingDao().findByName(name) ?: return Outcome.Failed("$name is not in the catalog")
        }

        // Temp copies of any Drive-only parts, pulled down so the frame-copy join (which reads local
        // file descriptors) can see them. Always cleaned up — on success as a visible step, on any
        // failure in the finally below — so a merge never leaves downloads in the cache.
        val tempFiles = mutableListOf<File>()
        try {
            // 0. Resolve each part to a local source. A Drive-only part is downloaded to a temp cache
            //    file first; this is what lets a DRIVE-storage user merge at all (their local copies
            //    were deleted after upload). The download phase is announced only when one is needed.
            if (entries.any { it.localUri == null }) onPhase(MergePhase.DOWNLOADING)
            val sources = entries.map { entry ->
                entry.localUri?.let(Uri::parse) ?: run {
                    val driveUri = entry.driveUri?.let(Uri::parse)
                        ?: return Outcome.Refused("${entry.displayName} is not on the phone or in Drive")
                    val temp = downloadToCache(context, driveUri, entry.displayName)
                        ?: return Outcome.Failed("Couldn't download ${entry.displayName} from Drive — check your connection")
                    tempFiles += temp
                    Uri.fromFile(temp)
                }
            }

            val folderUri = AppPreferences(context).getRecordingFolderUri()
                ?: return Outcome.Failed("No recordings folder is set")
            val extension = primaryName.substringAfterLast('.', "m4a")
            val mime = mimeFor(extension)

            // 1. Join into app-private staging. Nothing of the user's is touched yet.
            onPhase(MergePhase.MERGING)
            val staged = SafHelper.createAudioFile(context, folderUri, primaryName, mime, recoverable = false)
                ?: return Outcome.Failed("Could not open the recordings folder for writing")
            val stagingFile = staged.stagingFile
                ?: run {
                    runCatching { staged.descriptor.close() }
                    return Outcome.Failed("Expected a staged file to verify before deleting anything")
                }

            val boundaries = try {
                openAll(context, sources) { fds ->
                    AudioConcat.concat(fds, staged.descriptor.fileDescriptor, onPartProgress)
                }
            } catch (e: MergeFormat.Incompatible) {
                cleanUp(staged.descriptor, stagingFile)
                return Outcome.Refused(e.message ?: "These calls were not recorded the same way")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Joining failed", e)
                cleanUp(staged.descriptor, stagingFile)
                return Outcome.Failed(e.message ?: "The recordings could not be joined")
            }
            runCatching { staged.descriptor.close() }

            // 2. VERIFY the staged file before anything is deleted.
            val expected = boundaries.sumOf { it.frameCount }
            val actual = runCatching {
                FileInputStream(stagingFile).use { MergeFormat.frameCount(it.fd) }
            }.getOrElse { e ->
                AppLogger.e(TAG, "Could not re-open the merged file to verify it", e)
                stagingFile.delete()
                return Outcome.Failed("The merged recording could not be read back")
            }
            if (actual != expected) {
                AppLogger.e(TAG, "Verification failed: expected $expected frames, read $actual. Nothing deleted.")
                stagingFile.delete()
                return Outcome.Failed("The merged recording was incomplete, so nothing was changed")
            }

            // 3. Flatten the manifest. A part that was itself merged contributes its own parts, never
            //    itself, so un-merge is always one level and partName always names an original call.
            val manifest = flatten(context, entries, boundaries)

            // 4. Publish into the user's folder and PROVE it is there before anything of theirs goes: the
            //    published document must read back at exactly the staged size. Until then the originals
            //    are untouched, so a failed or short publish costs nothing but this merge (review C4).
            val mergedName = freeName(context, primaryName)
            val stagedSize = stagingFile.length()
            val published = SafHelper.publishStagedRecording(context, folderUri, mergedName, mime, stagingFile)
            if (published == null) {
                AppLogger.e(TAG, "Publishing $mergedName failed; the originals are kept and the staged merge discarded")
                stagingFile.delete()
                return Outcome.Failed("The merged recording could not be saved to your folder")
            }
            val size = SafHelper.fileSize(context, published)
            if (stagedSize <= 0L || size != stagedSize) {
                AppLogger.e(TAG, "Published $mergedName reads back as $size bytes, staged $stagedSize; originals kept, published copy removed")
                SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, published), mergedName)
                stagingFile.delete()
                return Outcome.Failed("The merged recording could not be saved to your folder")
            }
            stagingFile.delete()

            // 5. Catalog the merged recording with its manifest and metadata, still before any delete:
            //    the metadata is copied from the parts, so it has to be read while they exist.
            RecordingCatalog.recordLocal(
                context, mergedName, published, size,
                entries.first().lastModified.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
            RecordingCatalog.setDuration(context, mergedName, boundaries.sumOf { it.durationUs } / 1_000_000L)
            val stamped = manifest.map { it.copy(mergedName = mergedName) }
            db.mergePartDao().insertAll(stamped)
            MergeMetadata.onMerge(context, mergedName, stamped)

            // 6. Only now is it safe to remove what the user had. removeEverywhere drops both the local
            //    and the Drive copy of each consumed part — a Drive-only part's Drive original is
            //    replaced by the merged file, same as a local merge replaces the local originals.
            val keepOriginals = AppPreferences(context).isKeepOriginalsAfterMerge()
            if (!keepOriginals) entries.forEach { retireMergedInput(context, it) }

            // Same reason as on the way back: the merged recording is a new file, and the parts' Drive
            // copies were just deleted, so without this a library kept in Drive loses the conversation.
            StorageRouter.route(context, published, mergedName, mime)

            // 7. The merge is done; clean up the downloads as a visible step before reporting success.
            if (tempFiles.isNotEmpty()) {
                onPhase(MergePhase.DELETING_TEMP)
                tempFiles.forEach { runCatching { it.delete() } }
                tempFiles.clear()
            }
            onPhase(MergePhase.COMPLETED)

            AppLogger.i(TAG, "Merged ${names.size} calls into $mergedName (${manifest.size} parts, $actual frames)")
            return Outcome.Merged(mergedName, manifest.size)
        } finally {
            // Anything still here means we left on an error path — never keep orphaned downloads.
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Pulls a Drive-only recording down to an app-private cache file so the frame-copy join can read
     * it by file descriptor. Returns null (not throwing) on any read/write failure — offline, a gone
     * Drive file — so the caller can report an honest "couldn't download" rather than crash the merge.
     */
    private fun downloadToCache(context: Context, driveUri: Uri, displayName: String): File? = runCatching {
        val dir = File(context.cacheDir, "merge-temp").apply { mkdirs() }
        // Unique per attempt so two merges of the same call cannot collide on the cache file.
        val temp = File(dir, "${System.nanoTime()}-$displayName")
        context.contentResolver.openInputStream(driveUri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (temp.length() <= 0L) {
            temp.delete()
            return null
        }
        temp
    }.getOrElse { e ->
        AppLogger.w(TAG, "Could not download '$displayName' from Drive for merge: ${e.message}")
        null
    }

    /**
     * Cuts [mergedName] back into the calls it was made from and removes it.
     *
     * Returns every original, not the things that were ticked: a merge of an already-merged call was
     * flattened, so its parts are originals too. The confirmation shown before this runs lists them,
     * because "I merged two and got three back" is otherwise a genuine surprise.
     */
    suspend fun unMerge(
        context: Context,
        mergedName: String,
        /** (index, finished) as each part is cut and then restored, for the progress list. */
        onPartProgress: (Int, Boolean) -> Unit = { _, _ -> },
    ): Outcome {
        val db = RecordingDatabase.get(context)
        val parts = db.mergePartDao().partsOf(mergedName)
        if (parts.isEmpty()) return Outcome.Refused("This recording was not made by merging")

        val entry = db.recordingDao().findByName(mergedName)
            ?: return Outcome.Failed("$mergedName is not in the catalog")
        val source = entry.localUri?.let(Uri::parse)
            ?: return Outcome.Refused("The merged recording must be on the phone to take apart")
        val folderUri = AppPreferences(context).getRecordingFolderUri()
            ?: return Outcome.Failed("No recordings folder is set")

        // 1. Cut into staging, so a failure leaves the merged recording untouched. filesDir, not cacheDir:
        //    Android may clear a cache under storage pressure, mid-publish.
        val staging = File(context.filesDir, "unmerge").apply { deleteRecursively(); mkdirs() }
        val targets = parts.map { File(staging, it.partName) }
        val cuts = parts.map { AudioSplit.Cut(it.frameStart, it.frameCount, it.encoderDelayUs, it.encoderPaddingUs) }
        val ok = runCatching {
            context.contentResolver.openFileDescriptor(source, "r")!!.use { pfd ->
                val outs = targets.map { java.io.RandomAccessFile(it, "rw").apply { setLength(0) } }
                try {
                    AudioSplit.split(pfd.fileDescriptor, cuts, outs.map { it.fd }) { onPartProgress(it, false) }
                } finally {
                    outs.forEach { runCatching { it.close() } }
                }
            }
        }.onFailure { AppLogger.e(TAG, "Splitting failed; the merged recording is untouched", it) }.isSuccess
        if (!ok) {
            staging.deleteRecursively()
            return Outcome.Failed("The recording could not be taken apart, so nothing was changed")
        }

        // 2. VERIFY every part before removing the merged file.
        parts.forEachIndexed { i, part ->
            val frames = runCatching { FileInputStream(targets[i]).use { MergeFormat.frameCount(it.fd) } }.getOrDefault(-1)
            if (frames != part.frameCount) {
                AppLogger.e(TAG, "${part.partName}: expected ${part.frameCount} frames, got $frames. Nothing deleted.")
                staging.deleteRecursively()
                return Outcome.Failed("A recovered call was incomplete, so nothing was changed")
            }
        }

        // 3. Publish every part and read each back at its staged size, while the merged recording is
        //    still there. Any part that does not make it undoes the ones that did, and the merged call,
        //    its manifest and its metadata stay exactly as they were (review C6).
        val mime = mimeFor(mergedName.substringAfterLast('.', "m4a"))
        val published = mutableListOf<Uri>()
        parts.forEachIndexed { i, part ->
            val uri = publishVerified(context, folderUri, part.partName, mime, targets[i])
            if (uri == null) {
                AppLogger.e(TAG, "Could not restore ${part.partName}; undoing ${published.size} restored part(s), $mergedName kept")
                published.forEachIndexed { j, done ->
                    SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, done), parts[j].partName)
                }
                staging.deleteRecursively()
                return Outcome.Failed("A call could not be restored to your folder, so nothing was changed")
            }
            published += uri
            onPartProgress(i, true)
        }

        // 4. Catalogue every part before the merged recording may go: until a part has its row it is a hidden part,
        //    and deleting the merged call would take its kept data with it (N2). A row write that fails undoes the
        //    rows and files of this un-merge and keeps the merged call, as a failed publish does.
        val rowFailure = catalogueParts(context, parts, published)
        if (rowFailure != null) {
            AppLogger.e(TAG, "Could not catalogue ${rowFailure.first}; undoing this un-merge, $mergedName kept: ${rowFailure.second}")
            published.forEachIndexed { j, done ->
                SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, done), parts[j].partName)
            }
            staging.deleteRecursively()
            return Outcome.Failed("A call could not be restored to your library, so nothing was changed")
        }
        parts.forEachIndexed { i, part ->
            val uri = published[i]
            RecordingCatalog.setDuration(context, part.partName, part.durationUs / 1_000_000L)
            // A restored call is a new file, so it needs sending wherever recordings go — otherwise
            // a part that lived in Drive before the merge comes back device-only, and the copy the
            // user actually relies on is silently missing. Routed rather than copied directly, so it
            // follows the current target and schedule instead of replaying an old state.
            StorageRouter.route(context, uri, part.partName, mime)
        }
        removeEverywhere(context, entry)
        db.mergePartDao().deleteParts(mergedName)
        MergeMetadata.onUnMerge(context, mergedName)
        staging.deleteRecursively()

        AppLogger.i(TAG, "Un-merged $mergedName back into ${parts.size} calls")
        return Outcome.UnMerged(parts.size)
    }

    /**
     * Writes each part's catalog row for its [published] copy. On the first failure, puts back every row it had
     * changed (a part catalogued before keeps its earlier row; a hidden part loses only its new row, never its kept
     * data) and returns (part name, reason); null when every row was written.
     */
    private suspend fun catalogueParts(
        context: Context,
        parts: List<MergePartEntry>,
        published: List<Uri>,
    ): Pair<String, String>? {
        val dao = RecordingDatabase.get(context).recordingDao()
        val before = mutableListOf<Pair<String, RecordingEntry?>>()
        for ((i, part) in parts.withIndex()) {
            val previous = runCatching { dao.findByName(part.partName) }.getOrElse { e ->
                undoRows(context, before)
                return part.partName to (e.message ?: e.javaClass.simpleName)
            }
            before += part.partName to previous
            val uri = published[i]
            val failure = RecordingCatalog.recordLocalReporting(
                context, part.partName, uri, SafHelper.fileSize(context, uri), part.originalLastModified
            )
            if (failure != null) {
                undoRows(context, before)
                return part.partName to (failure.message ?: failure.javaClass.simpleName)
            }
        }
        return null
    }

    /** Puts the rows [catalogueParts] changed back as they were, without touching any part's kept data. */
    private suspend fun undoRows(context: Context, before: List<Pair<String, RecordingEntry?>>) {
        val dao = RecordingDatabase.get(context).recordingDao()
        before.forEach { (name, previous) ->
            runCatching { if (previous != null) dao.upsert(previous) else RecordingCatalog.forgetName(context, name) }
                .onFailure { AppLogger.e(TAG, "Could not undo the row for $name after a failed un-merge: ${it.message}") }
        }
    }

    /**
     * Publishes [staged] as [name] and returns its URI only when the published document reads back at the
     * staged size; otherwise removes whatever was published, logs why, and returns null.
     */
    private fun publishVerified(context: Context, folderUri: Uri, name: String, mime: String, staged: File): Uri? {
        val stagedSize = staged.length()
        val uri = SafHelper.publishStagedRecording(context, folderUri, name, mime, staged) ?: run {
            AppLogger.e(TAG, "Publishing $name failed")
            return null
        }
        val size = SafHelper.fileSize(context, uri)
        if (stagedSize > 0L && size == stagedSize) return uri
        AppLogger.e(TAG, "Published $name reads back as $size bytes, staged $stagedSize; removing it")
        SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, uri), name)
        return null
    }

    /** Runs [block] with every source open, closing them all whatever happens. */
    private fun <T> openAll(context: Context, uris: List<Uri>, block: (List<java.io.FileDescriptor>) -> T): T {
        val opened = mutableListOf<android.os.ParcelFileDescriptor>()
        try {
            uris.forEach { uri ->
                opened += context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Could not open $uri")
            }
            return block(opened.map { it.fileDescriptor })
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    /** Reads each input's existing manifest, then hands the arithmetic to [MergeManifest]. */
    private suspend fun flatten(
        context: Context,
        entries: List<RecordingEntry>,
        boundaries: List<AudioConcat.PartBoundary>,
    ): List<MergePartEntry> {
        val dao = RecordingDatabase.get(context).mergePartDao()
        val inputs = entries.map { entry ->
            MergeManifest.Input(
                displayName = entry.displayName,
                lastModified = entry.lastModified,
                existingParts = dao.partsOf(entry.displayName),
            )
        }
        return MergeManifest.flatten(inputs, boundaries)
    }

    /**
     * Takes a merged input out of the library once the merged recording holds it (owner decision N2, option A).
     *
     * Its audio goes, and its catalog row, but NOT its sidecars, transcript, notes, tags, star, marks, speaker turns or
     * summary: those stay, hidden by [HiddenMergeParts], so un-merge gives them back. An input that was itself a
     * merge is different: un-merge returns its originals (whose data the new manifest now holds), never it, so its
     * old manifest is dropped first and then its own copied data goes with it.
     */
    private suspend fun retireMergedInput(context: Context, entry: RecordingEntry) {
        // Only the audio: its .md and .json stay beside it, like the rest of its kept data, because the .json is
        // written only when a call is recorded and could never be made again for un-merge. They go when the last
        // merged call holding this part is deleted for good (see HiddenMergeParts).
        deleteFiles(context, entry)
        val dao = RecordingDatabase.get(context).mergePartDao()
        if (dao.partsOf(entry.displayName).isNotEmpty()) {
            dao.deleteParts(entry.displayName)
            RecordingCatalog.removeName(context, entry.displayName)
            // Nothing will ever restore this merged input itself, so its own sidecars go with its data.
            RecordingSidecars.deleteFor(context, listOf(entry.displayName))
        } else {
            RecordingCatalog.forgetName(context, entry.displayName)
        }
    }

    private fun deleteFiles(context: Context, entry: RecordingEntry) {
        listOfNotNull(entry.localUri, entry.driveUri).forEach { raw ->
            val uri = Uri.parse(raw)
            SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, uri), entry.displayName)
        }
    }

    /** Deletes every copy of a recording and drops its catalog row. */
    private suspend fun removeEverywhere(context: Context, entry: RecordingEntry) {
        deleteFiles(context, entry)
        RecordingCatalog.removeName(context, entry.displayName)
    }

    /**
     * The name to publish under: the primary's, with the millisecond in its timestamp nudged.
     *
     * It would read more neatly to reuse the primary's name exactly — the merged call *is* that
     * conversation continued — but the primary is also part 1 of the merge, and reusing the name
     * makes it its own part. That self-reference bites twice: the manifest would say a recording is
     * contained in itself, and un-merging would delete the very metadata rows it needs to restore.
     * A distinct name costs nothing visible, because the template still parses and the date shown is
     * identical to the minute (see [MergeNaming]), and it keeps both of those cases from ever arising.
     */
    private suspend fun freeName(context: Context, primaryName: String): String {
        val dao = RecordingDatabase.get(context).recordingDao()
        val dot = primaryName.lastIndexOf('.')
        val stem = if (dot > 0) primaryName.substring(0, dot) else primaryName
        val ext = if (dot > 0) primaryName.substring(dot) else ""
        (1..999).forEach { bump ->
            val candidate = MergeNaming.bumpedStem(stem, bump) + ext
            if (dao.findByName(candidate) == null) return candidate
        }
        return primaryName
    }

    private fun mimeFor(extension: String) =
        if (extension.equals("ogg", ignoreCase = true)) "audio/ogg" else "audio/mp4"

    private fun cleanUp(descriptor: android.os.ParcelFileDescriptor, staging: File) {
        runCatching { descriptor.close() }
        staging.delete()
    }
}
