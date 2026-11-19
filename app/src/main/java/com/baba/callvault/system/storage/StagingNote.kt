/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.io.File

/**
 * Where a staged recording was going, written beside it the moment it starts.
 *
 * A recording lives in private storage until the call ends and it is published. If the phone kills
 * CallVault mid-call, nothing publishes it — and without this note nobody knows its name, folder or type.
 * Plain `key=value` lines rather than JSON so it parses in unit tests and survives a half-written file:
 * anything incomplete decodes to null and is treated as "no note".
 */
data class StagingNote(
    val folderUri: String,
    val fileName: String,
    val mimeType: String,
    val startedAtMillis: Long,
) {
    fun encode(): String = listOf(
        "v=1",
        "folder=$folderUri",
        "name=$fileName",
        "mime=$mimeType",
        "started=$startedAtMillis",
    ).joinToString("\n")

    companion object {
        /** Appended to the staged file's name: `rec_stage_123.tmp` → `rec_stage_123.tmp.note`. */
        const val SUFFIX = ".note"

        fun fileFor(staged: File): File = File(staged.parentFile, staged.name + SUFFIX)

        /**
         * Deletes a staged recording that was published or discarded, and its note with it (review N19: the note
         * was left behind until the next rescue sweep). Returns whether the recording is gone; the caller logs a false.
         * The recording goes first and the note only once it is gone: a recording that could not be deleted keeps its
         * note, so the rescue can still save it under its own name and folder.
         */
        fun deleteWithNote(staged: File): Boolean {
            staged.delete()
            if (staged.exists()) return false
            fileFor(staged).delete()
            return true
        }

        fun decode(text: String): StagingNote? {
            val fields = text.lineSequence()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            return StagingNote(
                folderUri = fields["folder"]?.takeIf { it.isNotBlank() } ?: return null,
                fileName = fields["name"]?.takeIf { it.isNotBlank() } ?: return null,
                mimeType = fields["mime"]?.takeIf { it.isNotBlank() } ?: return null,
                startedAtMillis = fields["started"]?.toLongOrNull() ?: return null,
            )
        }
    }
}
