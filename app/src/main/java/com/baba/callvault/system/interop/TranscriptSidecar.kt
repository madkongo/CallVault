/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.interop

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.LibraryText
import com.baba.callvault.data.transcripts.export.ExportLabels
import com.baba.callvault.data.transcripts.export.TranscriptExport
import com.baba.callvault.data.transcripts.export.TranscriptFormat
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.first

/**
 * Writes a recording's transcript + notes (+ summary) as a `.md` file beside the audio, in the user's
 * recording folder, so copying the folder to a PC carries the text along with the audio.
 *
 * Opt-in and off by default ([AppPreferences.isWriteTranscriptSidecarEnabled]): it puts a second file
 * in the user's folder, and writing transcripts out is a privacy choice, so it waits to be asked.
 *
 * The sidecar is named like the audio with a `.md` extension (`call_in_….ogg` → `call_in_….md`), reusing
 * the app's own transcript renderer, so the file reads exactly like the on-screen transcript/notes. It is
 * rewritten when a transcript completes or a note is saved, removed when the recording is deleted (see
 * [com.baba.callvault.data.recordings.RecordingsRepository.deleteRecording]), and never throws — a backup
 * convenience must not turn a successful recording into a failed one.
 */
object TranscriptSidecar {

    private const val TAG = "CV:TranscriptSidecar"
    private val FORMAT = TranscriptFormat.MARKDOWN

    /** The audio file's name with its extension replaced by the sidecar's (`.md`). */
    fun sidecarNameFor(audioName: String): String =
        audioName.substringBeforeLast('.', audioName) + "." + FORMAT.extension

    /**
     * Writes the sidecar for [displayName], or removes it when there is nothing (no transcript, summary
     * or note) to write. No-op when the setting is off. Never throws.
     */
    suspend fun writeOrClear(context: Context, displayName: String) {
        val prefs = AppPreferences(context)
        if (!prefs.isWriteTranscriptSidecarEnabled()) return
        val folderUri = prefs.getRecordingFolderUri()
        if (folderUri == null) {
            AppLogger.w(TAG, "No recording folder; not writing a transcript sidecar for '$displayName'.")
            return
        }
        runCatching {
            val content = buildContent(context, displayName)
            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: error("folder $folderUri could not be opened")
            val name = sidecarNameFor(displayName)
            // Replace rather than accumulate "name (1).md"; this also removes a stale sidecar when the
            // transcript/note has been emptied (content is then null and we stop here).
            folder.findFile(name)?.delete()
            if (content.isNullOrBlank()) {
                AppLogger.d(TAG, "Nothing to write for '$displayName'; sidecar left absent.")
                return@runCatching
            }
            val doc = folder.createFile(FORMAT.mimeType, name) ?: error("could not create $name")
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(content.toByteArray()) }
                ?: error("could not open $name for writing")
            AppLogger.i(TAG, "Wrote transcript sidecar '$name'.")
        }.onFailure {
            AppLogger.w(TAG, "Could not write the transcript sidecar for '$displayName': ${it.message}")
        }
    }

    /**
     * Best-effort pass to write a sidecar for every recording that has a transcript or a note — used when
     * the setting is first turned on, so existing text is backed up too. No-op when the setting is off.
     */
    suspend fun backfillAll(context: Context) {
        if (!AppPreferences(context).isWriteTranscriptSidecarEnabled()) return
        runCatching {
            RecordingCatalog.all(context).forEach { entry -> writeOrClear(context, entry.displayName) }
            AppLogger.i(TAG, "Transcript sidecar backfill finished.")
        }.onFailure { AppLogger.w(TAG, "Transcript sidecar backfill failed: ${it.message}") }
    }

    /** The rendered transcript + notes (+ summary), or null when the recording has none of them. */
    private suspend fun buildContent(context: Context, displayName: String): String? {
        val title = displayName.substringBeforeLast('.', displayName)
        val labels = labelsFor(context)
        // exportDocument already folds in the note (and summary) when a transcript or summary exists.
        val doc = LibraryText.exportDocument(context, displayName, title, speakerNames = null)
        if (doc != null) return TranscriptExport.render(FORMAT, doc, labels).takeIf { it.isNotBlank() }
        // Note-only recording (no transcript, no summary): still worth a small notes file.
        val note = RecordingExtrasRepository.note(context, displayName).first()
        if (note.isBlank()) return null
        return buildString {
            append("# ").append(title).append("\n\n")
            append("## ").append(labels.notes).append("\n\n")
            append(note).append("\n")
        }
    }

    /** The same export headings [com.baba.callvault.ui.common.rememberExportLabels] builds, but off the UI thread. */
    private fun labelsFor(context: Context): ExportLabels = ExportLabels(
        summary = context.getString(R.string.summary_card_title),
        notes = context.getString(R.string.playback_note_title),
        transcript = context.getString(R.string.export_heading_transcript),
        keyPoints = context.getString(R.string.summary_card_key_points),
        decisions = context.getString(R.string.summary_card_decisions),
        actionItems = context.getString(R.string.summary_card_actions),
        keyFacts = context.getString(R.string.summary_card_facts),
        language = context.getString(R.string.transcription_language_label),
        model = context.getString(R.string.transcription_model_label),
    )
}
