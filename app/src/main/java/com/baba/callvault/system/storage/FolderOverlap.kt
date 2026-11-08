/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.net.Uri
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

    /** "primary:Alarms" holds "primary:Alarms/x"; "primary:" (the storage root) holds everything on it. */
    private fun contains(parent: String, child: String): Boolean {
        if (parent == child) return true
        val prefix = if (parent.endsWith(":") || parent.endsWith("/")) parent else "$parent/"
        return child.startsWith(prefix)
    }

    private fun treeId(uri: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()

    private fun documentId(uri: Uri): String? = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
}
