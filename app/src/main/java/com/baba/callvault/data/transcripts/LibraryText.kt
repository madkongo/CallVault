/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.export.ExportDocument
import com.baba.callvault.data.transcripts.export.ExportLabels
import com.baba.callvault.data.transcripts.export.TranscriptExport
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import com.baba.callvault.summary.CallSummary
import kotlinx.coroutines.flow.first

/**
 * Reading what a library row has to say, once, for a share or a save.
 *
 * The reading view gets all of this from flows, because it is on screen while a summary is being
 * written and has to redraw when one lands. A row's menu is a tap: it wants one answer, now, and
 * then it is finished — so these are plain suspending reads rather than a second set of observers
 * hung off every visible line of a list that can run to thousands.
 *
 * **Everything is read through [TranscriptDatabase.exists] first**, the guard the whole
 * [LibraryCounts] object exists for: nothing here may create `transcripts.db` on a phone that has
 * never transcribed. In practice a row cannot be on screen without one, so a null here is a row that
 * went away underneath the menu — which is why every function answers null rather than throwing.
 *
 * Nothing here is ever logged. A transcript and a summary are both accounts of a private call.
 */
object LibraryText {

    /**
     * One transcript as plain text, or null when there is none.
     *
     * The same string the reading view's "Text" share sends, from the same function, so a row and
     * the page it opens cannot come to disagree about what the transcript says.
     */
    suspend fun transcriptText(
        context: Context,
        displayName: String,
        speakerNames: SpeakerNames?,
    ): String? {
        val segments = segmentsOf(context, displayName) ?: return null
        return TranscriptExport.plainText(segments, speakerNames).takeIf { it.isNotBlank() }
    }

    /**
     * One summary as plain text, or null when there is none stored.
     *
     * No heading marks: this is a message, not a document — see
     * [TranscriptExport.renderSummary] for why the two share one renderer and differ only in those.
     */
    suspend fun summaryText(
        context: Context,
        displayName: String,
        labels: ExportLabels,
    ): String? {
        val summary = summaryOf(context, displayName) ?: return null
        return TranscriptExport.renderSummary(summary, labels, titleMark = "", sectionMark = "")
    }

    /**
     * Everything a file export needs for one recording, or null when there is nothing to write.
     *
     * The same document the reading view assembles, with the same rule about which summary reaches
     * it: only one actually stored. A row is asked for after the fact, so there is no
     * half-written card to guard against here — but the document is the same shape either way, and
     * one of them having a field the other does not is how an export quietly loses a note.
     *
     * Null only when the row has neither words nor a summary, which is a row that has gone.
     */
    suspend fun exportDocument(
        context: Context,
        displayName: String,
        title: String,
        speakerNames: SpeakerNames?,
    ): ExportDocument? {
        if (!TranscriptDatabase.exists(context)) return null
        val db = TranscriptDatabase.get(context)
        val stored = db.transcriptDao().observe(displayName).first()
        val summary = summaryOf(context, displayName)
        if (stored == null && summary == null) return null

        return ExportDocument(
            title = title,
            segments = stored?.segments.orEmpty(),
            speakerNames = speakerNames,
            summary = summary,
            note = RecordingExtrasRepository.note(context, displayName).first(),
            tags = TagRepository.tagsFor(context, displayName).first(),
            language = stored?.transcript?.language,
            model = stored?.transcript?.modelId,
        )
    }

    private suspend fun segmentsOf(context: Context, displayName: String) =
        if (!TranscriptDatabase.exists(context)) null
        else TranscriptDatabase.get(context).transcriptDao().observe(displayName).first()?.segments

    /**
     * The stored summary, parsed, or null.
     *
     * A row that will not parse answers null rather than an empty document, the same call
     * [com.baba.callvault.ui.common.rememberSummaryState] makes: a summary nobody can read is not a
     * summary, and sharing a blank one is worse than saying there was nothing to share.
     */
    private suspend fun summaryOf(context: Context, displayName: String): CallSummary? {
        if (!TranscriptDatabase.exists(context)) return null
        val stored = TranscriptDatabase.get(context).summaryDao().summary(displayName) ?: return null
        return CallSummary.parse(stored.document)
    }
}
