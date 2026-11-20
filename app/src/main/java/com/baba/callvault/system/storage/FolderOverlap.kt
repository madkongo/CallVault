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
import android.system.Os
import android.provider.DocumentsContract

/**
 * Whether two SAF folders, or two documents, are really the same place.
 *
 * Why: on 2.4.9 a user picked the recording folder (Alarms) as the "Drive" folder too, with "Drive only".
 * The copy step then found each new recording "already in Drive" — it was looking at the file itself — and
 * deleted the only copy. Every call was lost a second after it was saved (Xiaomi report, 2026-11-04).
 */
object FolderOverlap {

    /**
     * True when [a] and [b] are the same folder, or one is inside the other, on the same storage.
     * Folders on different providers (a phone folder and Google Drive) never overlap. Null overlaps nothing.
     */
    fun overlaps(a: Uri?, b: Uri?): Boolean {
        if (a == null || b == null) return false
        if (a.authority != b.authority) return false
        val idA = treeId(a) ?: return false
        val idB = treeId(b) ?: return false
        return contains(idA, idB) || contains(idB, idA)
    }

    /**
     * True when [a] and [b] name the same file, however each was reached. When either cannot be read as a
     * document, this answers true: the caller uses it to decide whether deleting is safe, and not deleting
     * is the safe mistake.
     */
    fun isSameDocument(a: Uri, b: Uri): Boolean {
        if (a.authority != b.authority) return false
        val idA = documentId(a) ?: return true
        val idB = documentId(b) ?: return true
        return idA == idB
    }

    /** Identifies a file on disk: the device and inode of an open descriptor. */
    data class FileId(val device: Long, val inode: Long)

    /**
     * [isSameDocument] plus the case it cannot see (review C55): one directory reached through two different providers
     * has two authorities and two document ids, so the "Drive copy" can be the device file itself and deleting the
     * device copy would delete the only one. Where both providers are the phone's own storage, both documents are opened
     * read-only and compared by device and inode. Any failure to tell answers true (keep). Cloud providers are never
     * opened for this: opening a Drive document can download it, and they are not the phone's files.
     */
    fun isSameFile(context: Context, a: Uri, b: Uri): Boolean =
        isSameFile(a, b) { fileIdOf(context, it) }

    internal fun isSameFile(a: Uri, b: Uri, fileId: (Uri) -> FileId?): Boolean {
        if (isSameDocument(a, b)) return true
        if (a.authority == b.authority) return false
        if (a.authority !in LOCAL_AUTHORITIES || b.authority !in LOCAL_AUTHORITIES) return false
        val idA = fileId(a) ?: return true
        val idB = fileId(b) ?: return true
        return idA == idB
    }

    /** Providers that serve the phone's own file system; the only ones the inode check opens. */
    internal val LOCAL_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
    )

    private fun fileIdOf(context: Context, uri: Uri): FileId? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use {
            val st = Os.fstat(it.fileDescriptor)
            FileId(st.st_dev, st.st_ino)
        }
    }.getOrNull()

    /** "primary:Alarms" holds "primary:Alarms/x"; "primary:" (the storage root) holds everything on it. */
    private fun contains(parent: String, child: String): Boolean {
        if (parent == child) return true
        val prefix = if (parent.endsWith(":") || parent.endsWith("/")) parent else "$parent/"
        return child.startsWith(prefix)
    }

    private fun treeId(uri: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

    private fun documentId(uri: Uri): String? = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
}
