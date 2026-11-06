/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.system.shareCallFiles
import com.baba.callvault.data.share.SharePart
import com.baba.callvault.data.share.ShareableCall
import com.baba.callvault.data.share.CallSharePlan
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.transcripts.LibraryRowActions
import com.baba.callvault.data.transcripts.LibraryText
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.transcripts.export.BulkTextShare
import com.baba.callvault.data.transcripts.export.ExportLabels
import com.baba.callvault.data.transcripts.export.TranscriptExport
import com.baba.callvault.data.transcripts.export.TranscriptExportFile
import com.baba.callvault.data.transcripts.export.TranscriptFormat
import com.baba.callvault.system.shareTranscriptFile
import com.baba.callvault.system.sharePlainText

/**
 * Sharing and saving a library row's text, off the screen that draws it.
 *
 * Here rather than in `HomeScreen` because none of it is composition: it is three database reads, a
 * render and an Intent. The screen keeps the state and raises the dialogs; this file does the work,
 * so the shell does not grow another two hundred lines for a menu item.
 *
 * Every function suspends and is launched from the screen's own scope. A share is a tap with a
 * person waiting, so a read that comes back with nothing says so out loud rather than leaving the
 * chooser not to appear — which reads as the tap not registering and invites a second one.
 */

/**
 * The neutral speaker labels, resolved in composition because that is the only place they can be.
 *
 * The contact's own name is not here: it is the row's title and differs per row. It is passed to
 * [speakerNamesFor] instead, exactly as the reading view passes it — a labelled line then reads as
 * part of the same conversation rather than introducing a second way to say who called.
 */
internal data class LibrarySpeakerLabels(val you: String, val sideA: String, val sideB: String)

@Composable
internal fun rememberLibrarySpeakerLabels(): LibrarySpeakerLabels = LibrarySpeakerLabels(
    you = stringResource(R.string.transcript_speaker_you),
    sideA = stringResource(R.string.transcript_speaker_a),
    sideB = stringResource(R.string.transcript_speaker_b),
)

/**
 * Who is who in [title]'s transcript, read fresh.
 *
 * Read per share rather than held for the life of the screen, for the reason the reading view gives:
 * the mapping is learned in the background from calls that happen while the app is running, and it
 * can be un-learned by deleting the calls that taught it. A stale map puts one person's words under
 * the other's name — in a file the user is about to hand to somebody else.
 */
private suspend fun speakerNamesFor(
    context: Context,
    title: String,
    labels: LibrarySpeakerLabels,
): SpeakerNames = SpeakerNames(
    map = SpeakerTurnsRepository.trustedMap(context),
    you = labels.you,
    contact = title,
    sideA = labels.sideA,
    sideB = labels.sideB,
)

/**
 * Writes one row's document in [format] and offers the file to the share sheet.
 *
 * The same three steps the reading view's export takes, in the same order and through the same
 * writer — assemble, write to the export cache, raise the chooser — so a row and the page it opens
 * produce the same file under the same name.
 */
internal suspend fun saveLibraryRow(
    context: Context,
    displayName: String,
    title: String,
    format: TranscriptFormat,
    speakers: LibrarySpeakerLabels,
    exportLabels: ExportLabels,
) {
    val document = LibraryText.exportDocument(
        context = context,
        displayName = displayName,
        title = title,
        speakerNames = speakerNamesFor(context, title, speakers),
    )
    if (document == null) {
        reportNothingToShare(context)
        return
    }

    val file = TranscriptExportFile.write(
        context = context,
        fileName = TranscriptExport.fileName(format, displayName),
        content = TranscriptExport.render(format, document, exportLabels),
    )
    // Reported rather than passed over, for the reason the reading view's export gives: the user
    // tapped a format and is waiting for a chooser, so silence reads as the tap not registering and
    // invites them to try again into the same full cache.
    if (file == null) reportExportFailed(context)
    else context.shareTranscriptFile(file, format.mimeType)
}

private fun reportNothingToShare(context: Context) {
    Toast.makeText(context, R.string.library_nothing_to_share, Toast.LENGTH_SHORT).show()
}

private fun reportExportFailed(context: Context) {
    Toast.makeText(context, R.string.transcript_export_failed, Toast.LENGTH_SHORT).show()
}

/** The words the Share sheet puts in headings and file names, in the user's language. */
internal data class ShareWords(
    val transcriptHeading: String,
    val summaryHeading: String,
    val transcriptFile: String,
    val summaryFile: String,
)

@Composable
internal fun rememberShareWords(): ShareWords = ShareWords(
    transcriptHeading = stringResource(R.string.share_part_transcript),
    summaryHeading = stringResource(R.string.share_part_summary),
    transcriptFile = stringResource(R.string.share_file_word_transcript),
    summaryFile = stringResource(R.string.share_file_word_summary),
)

/**
 * What the one Share sheet sends, for one call or a selection. See [CallSharePlan] for the rules.
 *
 * @param subject the selection's name (the page title) when several calls go together; one call goes under its own.
 */
internal suspend fun shareCalls(
    context: Context,
    calls: List<ShareableCall>,
    ticks: Set<SharePart>,
    subject: String,
    words: ShareWords,
    speakers: LibrarySpeakerLabels,
    exportLabels: ExportLabels,
) {
    suspend fun transcriptOf(call: ShareableCall): String? =
        if (SharePart.TRANSCRIPT in ticks && call.hasTranscript) {
            LibraryText.transcriptText(context, call.displayName, speakerNamesFor(context, call.title, speakers))
        } else null

    suspend fun summaryOf(call: ShareableCall): String? =
        if (SharePart.SUMMARY in ticks && call.hasSummary) LibraryText.summaryText(context, call.displayName, exportLabels)
        else null

    val title = calls.singleOrNull()?.title ?: subject

    if (!CallSharePlan.asFiles(ticks)) {
        val items = calls.mapNotNull { call ->
            CallSharePlan.combinedText(summaryOf(call), transcriptOf(call), words.summaryHeading, words.transcriptHeading)
                ?.let { BulkTextShare.Item(title = call.title, text = it) }
        }
        // One call goes without its name on top, as the pages always sent it; several are joined under their names.
        val text = items.singleOrNull()?.text ?: BulkTextShare.join(items)
        if (text.isBlank()) {
            reportNothingToShare(context)
            return
        }
        if (!BulkTextShare.mustBeAFile(text)) {
            context.sharePlainText(title, text)
            return
        }
        val file = TranscriptExportFile.write(context, "$title.${TranscriptFormat.TXT.extension}", text)
        if (file == null) reportExportFailed(context) else context.shareTranscriptFile(file, TranscriptFormat.TXT.mimeType)
        return
    }

    // Files: each call's audio, then its text files named after it, so they stay together in the chat or folder.
    val texts = mutableListOf<Pair<String, String>>()
    val order = mutableListOf<Any>() // a Uri for audio, an index into texts for a text file
    for (call in calls) {
        call.audioUri?.let { order += it.toUri() }
        transcriptOf(call)?.let { order += texts.size; texts += CallSharePlan.textFileName(call.displayName, words.transcriptFile) to it }
        summaryOf(call)?.let { order += texts.size; texts += CallSharePlan.textFileName(call.displayName, words.summaryFile) to it }
    }
    val files = if (texts.isEmpty()) emptyList() else TranscriptExportFile.writeAll(context, texts)
    if (files == null) {
        reportExportFailed(context)
        return
    }
    val authority = "${context.packageName}.fileprovider"
    val uris = order.map { if (it is Int) FileProvider.getUriForFile(context, authority, files[it]) else it as android.net.Uri }
    if (uris.isEmpty()) {
        reportNothingToShare(context)
        return
    }
    context.shareCallFiles(uris, title)
}

/** The part a library page is about, which its Share starts with ticked. */
internal val LibraryRowActions.Page.sharePart: SharePart
    get() = when (this) {
        LibraryRowActions.Page.Transcripts -> SharePart.TRANSCRIPT
        LibraryRowActions.Page.Summaries -> SharePart.SUMMARY
    }

