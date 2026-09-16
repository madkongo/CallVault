/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

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
 * The text one row has to offer, or null when it has none.
 *
 * The page decides which text, which is the whole difference between the two menus: Transcripts
 * shares the words, Summaries shares the summary. Both are the same strings the reading view would
 * show, from the same renderers.
 */
private suspend fun textOf(
    context: Context,
    page: LibraryRowActions.Page,
    displayName: String,
    title: String,
    speakers: LibrarySpeakerLabels,
    exportLabels: ExportLabels,
): String? = when (page) {
    LibraryRowActions.Page.Transcripts ->
        LibraryText.transcriptText(context, displayName, speakerNamesFor(context, title, speakers))

    LibraryRowActions.Page.Summaries ->
        LibraryText.summaryText(context, displayName, exportLabels)
}

/** Shares one row's text into the share sheet, as text rather than as a file. */
internal suspend fun shareLibraryRow(
    context: Context,
    page: LibraryRowActions.Page,
    displayName: String,
    title: String,
    speakers: LibrarySpeakerLabels,
    exportLabels: ExportLabels,
) {
    val text = textOf(context, page, displayName, title, speakers, exportLabels)
    if (text.isNullOrBlank()) {
        reportNothingToShare(context)
        return
    }
    context.sharePlainText(title, text)
}

/**
 * Shares the text of several rows as one message, or as one file when there is too much of it.
 *
 * See [BulkTextShare.MAX_INLINE_CHARS] for the crash this chooses between: a large selection put
 * straight into the Intent takes the whole app down at `startActivity` rather than reporting
 * anything. A file travels as a URI and has no such limit.
 *
 * @param subject what the message is called — the section's own title, so a selection of transcripts
 *   arrives called "Transcripts" rather than after whichever row happened to be first.
 */
internal suspend fun shareLibraryRows(
    context: Context,
    page: LibraryRowActions.Page,
    rows: List<Pair<String, String>>,
    subject: String,
    speakers: LibrarySpeakerLabels,
    exportLabels: ExportLabels,
) {
    val items = rows.mapNotNull { (displayName, title) ->
        textOf(context, page, displayName, title, speakers, exportLabels)
            ?.let { BulkTextShare.Item(title = title, text = it) }
    }
    val joined = BulkTextShare.join(items)
    if (joined.isBlank()) {
        reportNothingToShare(context)
        return
    }

    if (!BulkTextShare.mustBeAFile(joined)) {
        context.sharePlainText(subject, joined)
        return
    }

    val file = TranscriptExportFile.write(
        context = context,
        fileName = "$subject.${TranscriptFormat.TXT.extension}",
        content = joined,
    )
    if (file == null) reportExportFailed(context)
    else context.shareTranscriptFile(file, TranscriptFormat.TXT.mimeType)
}

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
