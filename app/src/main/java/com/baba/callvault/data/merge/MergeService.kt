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
    ): Outcome {
        if (thenNames.isEmpty()) return Outcome.Refused("A merge needs at least two calls")

        val names = listOf(primaryName) + thenNames
        if (names.size != names.toSet().size) return Outcome.Refused("The same call was chosen twice")

        val db = RecordingDatabase.get(context)
        val entries = names.map { name ->
            db.recordingDao().findByName(name) ?: return Outcome.Failed("$name is not in the catalog")
        }
        val sources = entries.map { entry ->
            entry.localUri?.let(Uri::parse)
                ?: return Outcome.Refused("${entry.displayName} is only in Drive; it must be on the phone to merge")
        }

        val folderUri = AppPreferences(context).getRecordingFolderUri()
            ?: return Outcome.Failed("No recordings folder is set")
        val extension = primaryName.substringAfterLast('.', "m4a")
        val mime = mimeFor(extension)

        // 1. Join into app-private staging. Nothing of the user's is touched yet.
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

        // 4. Only now is it safe to remove what the user had.
        val keepOriginals = AppPreferences(context).isKeepOriginalsAfterMerge()
        if (!keepOriginals) entries.forEach { removeEverywhere(context, it) }

        // 5. Publish. With the originals gone the primary's name is free again, which is what makes
        //    the merged recording read as that conversation continued rather than as a new one.
        val mergedName = freeName(context, primaryName)
        val published = SafHelper.publishStagedRecording(context, folderUri, mergedName, mime, stagingFile)
            ?: return Outcome.Failed("The merged recording could not be saved to your folder")
        stagingFile.delete()

        val size = SafHelper.fileSize(context, published)
        RecordingCatalog.recordLocal(
            context, mergedName, published, size,
            entries.first().lastModified.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        RecordingCatalog.setDuration(context, mergedName, boundaries.sumOf { it.durationUs } / 1_000_000L)
        // Same reason as on the way back: the merged recording is a new file, and the parts' Drive
        // copies were just deleted, so without this a library kept in Drive loses the conversation.
        StorageRouter.route(context, published, mergedName, mime)
        val stamped = manifest.map { it.copy(mergedName = mergedName) }
        db.mergePartDao().insertAll(stamped)
        MergeMetadata.onMerge(context, mergedName, stamped)

        AppLogger.i(TAG, "Merged ${names.size} calls into $mergedName (${manifest.size} parts, $actual frames)")
        return Outcome.Merged(mergedName, manifest.size)
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

        // 1. Cut into staging, so a failure leaves the merged recording untouched.
        val staging = File(context.cacheDir, "unmerge").apply { deleteRecursively(); mkdirs() }
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

        // 3. Safe to remove the merged recording and publish the parts under their own names.
        removeEverywhere(context, entry)
        val mime = mimeFor(mergedName.substringAfterLast('.', "m4a"))
        var restored = 0
        parts.forEachIndexed { i, part ->
            val uri = SafHelper.publishStagedRecording(context, folderUri, part.partName, mime, targets[i])
            if (uri == null) {
                AppLogger.e(TAG, "Could not restore ${part.partName}")
                return@forEachIndexed
            }
            restored++
            RecordingCatalog.recordLocal(
                context, part.partName, uri, SafHelper.fileSize(context, uri), part.originalLastModified
            )
            RecordingCatalog.setDuration(context, part.partName, part.durationUs / 1_000_000L)
            // A restored call is a new file, so it needs sending wherever recordings go — otherwise
            // a part that lived in Drive before the merge comes back device-only, and the copy the
            // user actually relies on is silently missing. Routed rather than copied directly, so it
            // follows the current target and schedule instead of replaying an old state.
            StorageRouter.route(context, uri, part.partName, mime)
            onPartProgress(i, true)
        }
        db.mergePartDao().deleteParts(mergedName)
        MergeMetadata.onUnMerge(context, mergedName)
        staging.deleteRecursively()

        AppLogger.i(TAG, "Un-merged $mergedName back into $restored calls")
        return Outcome.UnMerged(restored)
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

    /** Deletes every copy of a recording and drops its catalog row. */
    private suspend fun removeEverywhere(context: Context, entry: RecordingEntry) {
        listOfNotNull(entry.localUri, entry.driveUri).forEach { raw ->
            val uri = Uri.parse(raw)
            SafHelper.deleteDocument(DocumentFile.fromSingleUri(context, uri), entry.displayName)
        }
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
     * identical to the second, and it keeps both of those cases from ever arising.
     */
    private suspend fun freeName(context: Context, primaryName: String): String {
        val dao = RecordingDatabase.get(context).recordingDao()
        val dot = primaryName.lastIndexOf('.')
        val stem = if (dot > 0) primaryName.substring(0, dot) else primaryName
        val ext = if (dot > 0) primaryName.substring(dot) else ""
        (1..999).forEach { bump ->
            val candidate = bumpMillis(stem, bump) + ext
            if (dao.findByName(candidate) == null) return candidate
        }
        return primaryName
    }

    /** Adds [by] to the millisecond field of a `yyyyMMdd_HHmmss.SSSZ` stem, leaving the rest alone. */
    private fun bumpMillis(stem: String, by: Int): String {
        val match = Regex("""^(\d{8}_\d{6})\.(\d{3})(.*)$""").find(stem) ?: return stem + "_" + by
        val (head, millis, tail) = match.destructured
        return "$head.${"%03d".format((millis.toInt() + by) % 1000)}$tail"
    }

    private fun mimeFor(extension: String) =
        if (extension.equals("ogg", ignoreCase = true)) "audio/ogg" else "audio/mp4"

    private fun cleanUp(descriptor: android.os.ParcelFileDescriptor, staging: File) {
        runCatching { descriptor.close() }
        staging.delete()
    }
}
