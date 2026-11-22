/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.io.File

/** What the app knows about how a recorder session that outlived the app ended; see [StagingNote.orphanStop]. */
enum class OrphanStop {
    NONE,

    /** The recorder confirmed it stopped this file; whether the call's end was inside it is not known. */
    STOPPED,

    /** As [STOPPED], and the stop came when the phone call ended: the file covers the call to its end. */
    CALL_ENDED,
}

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
    val orphanStop: OrphanStop = OrphanStop.NONE,
    /** Seconds the recording ran on after its call ended (only set past 30 s, by the orphan stop); 0 = not told. */
    val ranOnSeconds: Int = 0,
    /** The orphaned session was an app call (those never appear in the call log). */
    val appCall: Boolean = false,
) {
    fun encode(): String = listOf(
        "v=1",
        "folder=$folderUri",
        "name=$fileName",
        "mime=$mimeType",
        "started=$startedAtMillis",
    ).plus(if (orphanStop == OrphanStop.NONE) emptyList() else listOf("orphan=${orphanStop.name}"))
        .plus(if (ranOnSeconds > 0) listOf("ranon=$ranOnSeconds") else emptyList())
        .plus(if (appCall) listOf("appcall=1") else emptyList()).joinToString("\n")

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
                orphanStop = fields["orphan"]?.let { v -> OrphanStop.entries.firstOrNull { it.name == v } } ?: OrphanStop.NONE,
                ranOnSeconds = fields["ranon"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                appCall = fields["appcall"] == "1",
            )
        }

        /**
         * Writes [stop] into the note beside [staged]. Returns false when there is no readable note (nothing is written).
         * The mark travels with the file, whichever worker publishes it, even after it moved into waiting storage.
         */
        fun markOrphanStop(staged: File, stop: OrphanStop, ranOnSeconds: Int = 0, appCall: Boolean = false): Boolean {
            val noteFile = fileFor(staged)
            val note = noteFile.takeIf { it.exists() }?.let { runCatching { decode(it.readText()) }.getOrNull() } ?: return false
            return runCatching { noteFile.writeText(note.copy(orphanStop = stop, ranOnSeconds = ranOnSeconds, appCall = appCall).encode()); true }.getOrDefault(false)
        }
    }
}
