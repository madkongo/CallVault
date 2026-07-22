/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.utils.AppLogger
import java.io.File
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * SafHelper provides utility functions for working with the Android Storage Access Framework (SAF).
 *
 * Users explicitly grant access to a folder via the system document-tree picker.
 */
object SafHelper {

    private const val TAG = "CV:SafHelper"

    /** Prefix for the app-private staging temp files used when a provider rejects `"rw"`. */
    private const val STAGING_TEMP_PREFIX = "rec_stage_"
    private const val STAGING_TEMP_SUFFIX = ".tmp"

    /**
     * Holds the result of a successful [createAudioFile] call.
     *
     * @param uri         The content URI of the final destination file in the user's SAF folder.
     * @param descriptor  An open read-write [ParcelFileDescriptor] the muxer writes into. When
     *                    [stagingFile] is null this is the SAF file itself; when non-null it is the
     *                    seekable temp file (the SAF provider refused `"rw"`). Must be closed after use.
     * @param displayName A human-readable path for logging (e.g. "Recordings/call_incoming_….webm").
     * @param stagingFile Non-null when recording is staged to this internal temp file; the caller must
     *                    copy it into [uri] via [writeStagedFileToUri] once the container is finalised.
     */
    data class SafResult(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val displayName: String,
        val stagingFile: File? = null
    )

    /**
     * Creates a new audio file inside the user-chosen SAF folder and returns a **seekable read-write**
     * descriptor for [MediaMuxer][android.media.MediaMuxer] to write into (it seeks back to patch the
     * container index on finalize, so a write-only fd is not enough).
     *
     * Most providers hand out a `"rw"` fd for a freshly created file. Some — Downloads, SD-card, and
     * cloud/synced or certain OEM document providers — reject it with `FileNotFoundException:
     * "Unsupported mode: rw"`. For those we transparently **stage** the recording to an app-private temp
     * file (internal storage always gives a real seekable rw fd) and return it via [SafResult.stagingFile];
     * the caller copies it into the SAF file write-only at finalize (the same path [copyFileToFolder]
     * proves works on every provider). This never throws for the mode issue — it returns null only if the
     * folder/file truly cannot be created.
     *
     * @param context    App context used to resolve the [DocumentFile] and open the FD.
     * @param folderUri  The tree URI of the destination folder (from the document-tree picker).
     * @param fileName   The desired file name including extension (e.g. "call_incoming_….webm").
     * @param mimeType   The MIME type of the file (e.g. "audio/webm" for Opus, "audio/mp4" for AAC).
     * @return A [SafResult] with the URI, open FD, display name, and optional staging file; or null on failure.
     */
    fun createAudioFile(context: Context, folderUri: Uri, fileName: String, mimeType: String): SafResult? {
        val directory = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        if (!directory.canWrite()) return null

        val newFile = directory.createFile(mimeType, fileName) ?: return null
        val displayName = "${directory.name}/$fileName"

        // Preferred path: the provider gives a seekable rw fd directly (no staging, no copy).
        val directFd = runCatching { context.contentResolver.openFileDescriptor(newFile.uri, "rw") }
            .getOrElse { e ->
                AppLogger.w(TAG, "Provider refused \"rw\" for ${newFile.uri} (${e.message}); staging to internal temp")
                null
            }
        if (directFd != null) return SafResult(newFile.uri, directFd, displayName)

        // Fallback: record into an app-private seekable temp file, copy into the SAF file at finalize.
        val tempFile = runCatching { File.createTempFile(STAGING_TEMP_PREFIX, STAGING_TEMP_SUFFIX, context.cacheDir) }
            .getOrElse { e -> AppLogger.e(TAG, "Failed to create staging temp file", e); return null }
        val tempFd = runCatching {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }.getOrElse { e ->
            AppLogger.e(TAG, "Failed to open staging temp fd", e)
            runCatching { tempFile.delete() }
            return null
        }
        AppLogger.i(TAG, "Staging recording to ${tempFile.name} → will copy into ${newFile.uri} at finalize")
        return SafResult(newFile.uri, tempFd, displayName, stagingFile = tempFile)
    }

    /**
     * Streams a finished staged recording ([srcFile]) into the already-created SAF file at [destUri]
     * using a WRITE-ONLY stream — which works on the providers that reject `"rw"` (see [copyFileToFolder]).
     * Called at finalize when [createAudioFile] returned a [SafResult.stagingFile].
     *
     * @return true if the bytes were fully written; false on any failure (temp is then kept for recovery).
     */
    fun writeStagedFileToUri(context: Context, srcFile: File, destUri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(destUri, "wt")?.use { output ->
            srcFile.inputStream().use { input -> input.copyTo(output) }
        } ?: run {
            AppLogger.e(TAG, "openOutputStream returned null for $destUri")
            return false
        }
        true
    }.getOrElse { e ->
        AppLogger.e(TAG, "Failed to write staged recording into $destUri", e)
        false
    }

    /**
     * Returns true if [folderUri] points to an existing, writable SAF folder.
     * Used to validate the user's chosen recording folder before starting a session.
     *
     * @param context   App context used to resolve the [DocumentFile].
     * @param folderUri The tree URI to validate, or null.
     * @return true if the folder exists and is writable; false if null or inaccessible.
     */
    /**
     * SAF document-provider authorities that are cloud/synced and therefore unreliable as the LIVE
     * recording-capture folder: they reject `"rw"` and report file length asynchronously (0 right after
     * a write), which breaks in-progress capture and the empty-recording guard. They are perfectly fine
     * as the Drive *backup* target (that copy happens after the recording is finalised).
     */
    private val CLOUD_PROVIDER_AUTHORITIES = setOf(
        "com.google.android.apps.docs.storage",                          // Google Drive
        "com.google.android.apps.docs.storage.legacy",                   // Google Drive (legacy)
        "com.microsoft.skydrive.content.StorageAccessProvider",          // OneDrive
        "com.dropbox.product.android.dbapp.document_provider.documents", // Dropbox
    )

    /**
     * Returns true if [uri] is served by a known cloud/sync document provider (Google Drive, OneDrive,
     * Dropbox). Used to reject such folders as the capture destination and to warn about an existing one.
     */
    fun isCloudFolder(uri: Uri?): Boolean =
        uri?.authority?.let { it in CLOUD_PROVIDER_AUTHORITIES } == true

    @OptIn(ExperimentalContracts::class)
    fun isFolderValid(context: Context, folderUri: Uri?): Boolean {
        // Tells the compiler: if we returns true, folderUri is not null. Prevent false compiler error and warnings.
        contract {
            returns(true) implies (folderUri != null)
        }
        if (folderUri == null) return false
        val directory = DocumentFile.fromTreeUri(context, folderUri)
        return directory != null && directory.exists() && directory.canWrite()
    }

    /**
     * Returns a human-readable display name for a SAF folder URI.
     * Used in the Settings screen to show which folder recordings are saved to.
     *
     * @param context   App context used to resolve the [DocumentFile].
     * @param folderUri The tree URI, or null.
     * @return The folder name (e.g. "Recordings"), or null.
     */
    fun getFolderDisplayNameOrNull(context: Context, folderUri: Uri?): String? {
        if (folderUri == null) return null
        val directory = DocumentFile.fromTreeUri(context, folderUri)
        return directory?.name
    }

    /**
     * Copies [srcUri]'s content into [destFolderUri] as [displayName] using a WRITE-ONLY output
     * stream (works on cloud providers like Google Drive, unlike "rw"). Returns the new file Uri or null.
     *
     * @param context       App context used for content resolver operations.
     * @param srcUri        The content URI of the source file to copy.
     * @param destFolderUri The tree URI of the destination folder.
     * @param displayName   The desired file name for the copy (including extension).
     * @param mimeType      The MIME type of the file (e.g. "audio/ogg").
     * @return The content URI of the newly created copy, or null on failure.
     */
    fun copyFileToFolder(context: Context, srcUri: Uri, destFolderUri: Uri, displayName: String, mimeType: String): Uri? {
        val dir = DocumentFile.fromTreeUri(context, destFolderUri) ?: return null
        if (!dir.canWrite()) return null
        val dest = dir.createFile(mimeType, displayName) ?: return null
        return try {
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                context.contentResolver.openOutputStream(dest.uri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: return null
            } ?: return null
            dest.uri
        } catch (e: Exception) {
            runCatching { dest.delete() }
            null
        }
    }

    /**
     * Returns the byte length of the document at [uri], or -1 if unknown.
     *
     * @param context App context used to resolve the [DocumentFile].
     * @param uri     The content URI of the document to measure.
     * @return The file size in bytes, or -1 if unavailable.
     */
    fun fileSize(context: Context, uri: Uri): Long =
        runCatching { DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L }.getOrDefault(-1L)
}
