/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Where downloaded models live on disk, and whether one can be trusted.
 *
 * Serves any [DownloadableModel] — whisper models for transcription, and the language model for
 * summaries. Only [installedModels] is still whisper-specific, because it enumerates a catalogue
 * rather than acting on a model it was handed.
 *
 * Models are large — 190 MB to 874 MB for whisper, and about 3.5 GB for the summariser — and are
 * deliberately kept in the app's private files directory, never in the recordings folders: they must
 * not be swept by retention, synced to Drive, or shown to the user as if they were a recording.
 *
 * Two different checks, used for two different purposes:
 * - [verify] hashes the whole file. Correct but slow, so it runs **once**, straight after a download
 *   and before the file is given its real name.
 * - [isInstalled] only compares the length. Cheap enough to call from the UI, and sufficient to catch
 *   the realistic failure — a killed download leaving a short file. Hashing 574 MB every time the
 *   settings screen drew would be unusable.
 */
object ModelRepository {

    private const val TAG = "CV:ModelRepository"
    private const val MODELS_DIR = "models"

    /** Suffix for an in-progress download, so a partial file can never be mistaken for a real one. */
    const val PART_SUFFIX = ".part"

    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val DIGEST_BUFFER_BYTES = 1 shl 16

    /** The directory holding downloaded models, created on demand. */
    fun modelsDir(context: Context): File =
        File(context.applicationContext.filesDir, MODELS_DIR).apply { mkdirs() }

    /**
     * Whether [model] is present and complete in [dir].
     *
     * [expectedSize] exists so tests can use a stand-in file instead of writing hundreds of
     * megabytes; production callers leave it at the model's published length.
     */
    fun isInstalled(
        dir: File,
        model: DownloadableModel,
        expectedSize: Long = model.sizeBytes
    ): Boolean {
        val file = File(dir, model.fileName)
        return file.isFile && file.length() == expectedSize
    }

    fun isInstalled(context: Context, model: DownloadableModel): Boolean =
        isInstalled(modelsDir(context), model)

    /** The model's file, or null when it is absent or incomplete. */
    fun pathFor(
        dir: File,
        model: DownloadableModel,
        expectedSize: Long = model.sizeBytes
    ): File? = File(dir, model.fileName).takeIf { isInstalled(dir, model, expectedSize) }

    fun pathFor(context: Context, model: DownloadableModel): File? =
        pathFor(modelsDir(context), model)

    /** Every model currently installed and complete. */
    fun installedModels(context: Context): List<TranscriptionModel> {
        val dir = modelsDir(context)
        return TranscriptionModel.entries.filter { isInstalled(dir, it) }
    }

    /**
     * Whether [file]'s SHA-256 equals [expectedSha256], comparing case-insensitively.
     *
     * Streams the file rather than reading it, because these are hundreds of megabytes. Returns false
     * rather than throwing for a missing or unreadable file: every caller's response to "cannot be
     * trusted" is the same regardless of the reason.
     */
    fun verify(file: File, expectedSha256: String): Boolean {
        if (!file.isFile) return false

        return runCatching {
            val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(DIGEST_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.map { actual ->
            actual.equals(expectedSha256, ignoreCase = true)
        }.getOrElse {
            AppLogger.w(TAG, "Could not hash ${file.name}: ${it.message}")
            false
        }
    }

    /** What a new download attempt found on disk from an earlier one, after dealing with it. */
    sealed interface Leftover {
        /** Nothing there: download from the first byte. */
        data object None : Leftover

        /** A partial file that can be continued from [bytes]. */
        data class Resume(val bytes: Long) : Leftover

        /** A complete file that verified, and is now installed — nothing to download. */
        data object Installed : Leftover

        /** A file that could not be trusted — too long, or full length but damaged — and was deleted. */
        data object Discarded : Leftover
    }

    /**
     * Looks at what an earlier attempt left in `.part` and settles it before anything is fetched:
     *
     * - longer than the model: not this file; deleted;
     * - exactly the model's length: an attempt that finished writing but never got verified. Verified now —
     *   installed if it passes, **deleted if it is damaged**. Resuming a damaged full-length file would fetch
     *   nothing and fail the digest again on every try, which is how a user ends up seeing "the download was
     *   damaged" forever (report 2026-09-28);
     * - shorter: resumed from its length.
     *
     * [expectedSize] and [expectedSha256] default to the model's; tests pass small stand-ins.
     */
    fun settleLeftover(
        dir: File,
        model: DownloadableModel,
        expectedSize: Long = model.sizeBytes,
        expectedSha256: String = model.sha256,
    ): Leftover {
        val part = partFileFor(dir, model)
        if (!part.isFile) return Leftover.None
        val length = part.length()
        return when {
            length > expectedSize -> {
                AppLogger.w(TAG, "Deleting a leftover download of ${model.id} longer than the model (${ModelDownloadPolicy.mb(length)} > ${ModelDownloadPolicy.mb(expectedSize)})")
                part.delete()
                Leftover.Discarded
            }
            length == expectedSize ->
                if (finalizeDownload(dir, model, expectedSha256)) Leftover.Installed
                else {
                    AppLogger.w(TAG, "A complete leftover download of ${model.id} was damaged; deleted before downloading again")
                    part.delete()
                    Leftover.Discarded
                }
            else -> Leftover.Resume(length)
        }
    }

    /** The in-progress download file for [model]. */
    fun partFileFor(dir: File, model: DownloadableModel): File =
        File(dir, model.fileName + PART_SUFFIX)

    /**
     * How much of [model] is already on disk from an interrupted download, or 0.
     *
     * Exists so the UI can say so. A cancelled 3.46 GB download keeps its partial file on purpose —
     * the next attempt resumes with a Range request and the user pays for each byte once — but a
     * row that still reads "Download, 3.5 GB" hides that entirely, and looks exactly like starting
     * again from nothing.
     */
    fun partialBytes(context: Context, model: DownloadableModel): Long {
        val part = partFileFor(modelsDir(context), model)
        return if (part.isFile) part.length() else 0L
    }

    /**
     * Promotes a finished download to the real file, but only if it hashes correctly.
     *
     * This is the gate the whole download path exists to reach: a model is renamed into place only
     * after its digest matches, so a truncated or corrupted file can never be handed to ggml — whose
     * failure mode is a crash inside native code, not an error we could catch and report.
     *
     * A failed check deletes the partial file rather than leaving it: keeping it would only mean the
     * next attempt resumes from bytes already known to be wrong.
     *
     * @return true when the model is now installed.
     */
    fun finalizeDownload(
        dir: File,
        model: DownloadableModel,
        expectedSha256: String = model.sha256
    ): Boolean {
        val part = partFileFor(dir, model)
        if (!part.isFile) return false

        if (!verify(part, expectedSha256)) {
            AppLogger.w(TAG, "Discarding ${model.id}: downloaded file failed its digest check")
            part.delete()
            return false
        }

        val target = File(dir, model.fileName)
        if (target.exists()) target.delete()

        val renamed = part.renameTo(target)
        if (renamed) AppLogger.i(TAG, "Installed model ${model.id}")
        else AppLogger.w(TAG, "Verified ${model.id} but could not move it into place")
        return renamed
    }

    /**
     * Deletes model files that no known model claims, returning the bytes freed.
     *
     * **Why this has to exist.** [delete] only ever removes a file whose name matches a model that is
     * still in the code. So the moment a model entry changes its filename — a better quantisation, a
     * newer build — the old file becomes invisible to the app and impossible to remove from inside it.
     * Swapping the summariser on 2026-08-27 stranded **3.46 GB** on a real phone exactly this way.
     *
     * **The risk is entirely one-sided, and the design follows from that.** Failing to delete an orphan
     * wastes space; deleting the wrong file destroys a multi-gigabyte download the user made over
     * Wi-Fi. So [keep] is a **whitelist**: a file survives unless the caller has positively accounted
     * for it, an empty whitelist deletes nothing at all rather than everything, and directories are
     * never touched.
     *
     * A `.part` file is matched by its base name, so a download **in progress** — which is always for a
     * model that is by definition known — is kept.
     *
     * @param dir  the models directory; a missing one is not an error.
     * @param keep every filename any known model may occupy, from *all* model families sharing [dir].
     */
    fun pruneOrphans(dir: File, keep: Set<String>): Long {
        // An empty whitelist means the caller could not build its list, never "delete everything".
        if (keep.isEmpty() || !dir.isDirectory) return 0L

        var freed = 0L
        dir.listFiles().orEmpty()
            .filter { it.isFile }
            .filter { it.name.removeSuffix(PART_SUFFIX) !in keep }
            .forEach { file ->
                val size = file.length()
                if (file.delete()) {
                    freed += size
                    AppLogger.i(TAG, "Removed the orphaned model file ${file.name} ($size bytes)")
                } else {
                    AppLogger.w(TAG, "Could not remove the orphaned model file ${file.name}")
                }
            }
        return freed
    }

    /** Deletes [model] and any partial download of it. Safe to call when nothing is installed. */
    fun delete(context: Context, model: DownloadableModel) {
        val dir = modelsDir(context)
        listOf(File(dir, model.fileName), File(dir, model.fileName + PART_SUFFIX))
            .filter { it.exists() }
            .forEach { file ->
                if (file.delete()) AppLogger.i(TAG, "Deleted ${file.name}")
                else AppLogger.w(TAG, "Failed to delete ${file.name}")
            }
    }
}
