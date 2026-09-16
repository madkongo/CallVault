/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.TranscriptStatus
import com.baba.callvault.data.transcripts.TranscriptsPage
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.RecordingLabel
import com.baba.callvault.ui.common.TranscribingPillState
import com.baba.callvault.ui.common.TranscriptActionButton

/**
 * The Transcripts page: everything transcribed, and everything on its way to being.
 *
 * ## What it shows, and why in that order
 *
 * The finished transcripts are the page, and their count is exactly what the hub's card says —
 * [TranscriptsPage.Groups.ready] is the same DONE rows [com.baba.callvault.data.transcripts.LibraryCounts.transcribed]
 * counts, so the card and the page agree by construction rather than by two queries that happen to
 * match. Above them sit the two things the app otherwise never says out loud:
 *
 *  - **what is being transcribed**, because a run is minutes of CPU the user asked for and the only
 *    other sign of it is a pill beside a title on whatever screen they happen to be on;
 *  - **what failed**, because the scheduler deliberately never retries a FAILED row — so without a
 *    heading that names them, a transcription that died is invisible for ever unless the user
 *    scrolls the recordings list looking for a red icon.
 *
 * ## What it does not show
 *
 * **No snippet of the words.** It was wanted, and it is not cheap: the transcripts table holds no
 * text, so the only way to a first line is a group-by across every segment of every call — cost that
 * grows with how much the user has transcribed, on a screen they open to find something. That is the
 * exact shape of the list-load regression that made freshly recorded calls look missing. The row says
 * who the call was with and when, which is what recognises it.
 *
 * ## Why import lives here
 *
 * An imported file is not a call and has no business on a list of calls; the only reason to bring
 * one in is to read it. So the way in sits on the page that shows what has been read — above the
 * groups, and above the empty state too, because someone who has never transcribed anything is
 * precisely the person for whom importing is the answer.
 *
 * @param groups     Already grouped; see [TranscriptsPage] for what is kept and why.
 * @param recordings The catalog, resolved to rows **once for the whole list** rather than per row.
 *                   A transcript with no row in it is drawn from its own file name; see
 *                   [transcriptRows].
 * @param transcribing What the queue is doing, the same state the title pill reads. It supplies the
 *                   percentage inside a running row's ring, and its being anything other than
 *                   Hidden is what makes Stop worth offering.
 * @param listState  Hoisted by the caller: this page leaves composition on every section switch and
 *                   whenever a transcript is opened over it, taking any place in the list with it.
 * @param onOpenQueue Opens the queue sheet, which owns the Stop.
 * @param onOpen     Read a finished transcript.
 * @param onRetry    Try a failed one again.
 * @param onOpenAudio Opens a recording's own screen — used by the waiting group, where the audio is
 *                   still there and playing, sharing or deleting it are the other things to do.
 * @param onImport   Raises the file picker.
 * @param importing  True while a chosen file is being copied and checked. The card says so and
 *                   stops accepting taps: the copy is not instant for a long recording, and a second
 *                   picker opened over the first would import the same file twice.
 */
@Composable
fun TranscriptsScreen(
    groups: TranscriptsPage.Groups,
    recordings: List<RecordingItem>,
    transcribing: TranscribingPillState,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenAudio: (String) -> Unit,
    onImport: () -> Unit,
    importing: Boolean,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    // Resolved once for the whole page rather than per row: a row that looked itself up would put a
    // walk of the recordings list behind every visible line of the screen.
    val byName = recordings.associateBy { it.displayName }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.home_transcripts_title),
        onBack = onBack,
        titleTrailing = titleTrailing,
        actions = {
            // The same sheet the recordings list raises, not a field on this page. A hit is a moment
            // inside a call rather than a transcript, so inline results would replace this list with
            // rows that mean something else — and a second search would be a second FTS query to
            // keep correct, over an index whose quoting rules have already caused one crash.
            IconButton(onClick = onSearch) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(R.string.home_search_transcripts),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.home_open_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Before the empty-state branch below, which returns early. An empty Transcripts page is
            // exactly where importing needs to be offered — it is one of the two answers to "there
            // is nothing here yet", and the other one is on a different screen.
            item { ImportAudioCard(importing = importing, onImport = onImport) }

            if (groups.isEmpty) {
                item {
                    LibrarySectionEmpty(
                        title = stringResource(R.string.home_transcripts_empty_title),
                        hint = stringResource(R.string.home_transcripts_empty_hint),
                    )
                }
                return@LazyColumn
            }

            // Above everything, including what is running: this is audio sitting on the phone that
            // the user asked to have read and that nothing is currently reading. It is the one group
            // on this page that needs an action rather than attention.
            if (groups.waiting.isNotEmpty()) {
                item { CvSectionHeader(text = stringResource(R.string.transcripts_waiting_header)) }
                item {
                    Text(
                        text = stringResource(R.string.transcripts_waiting_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                waitingRows(names = groups.waiting, byName = byName, onTranscribe = onRetry, onOpen = onOpenAudio)
            }

            if (groups.working.isNotEmpty()) {
                item {
                    QueueHeader(
                        isRunning = transcribing != TranscribingPillState.Hidden,
                        onOpenQueue = onOpenQueue,
                    )
                }
                transcriptRows(
                    entries = groups.working,
                    byName = byName,
                    // Nothing to open: the words do not exist yet. A card that took a tap and did
                    // nothing would read as the app having missed it.
                    onOpen = null,
                    onRetry = onRetry,
                    percentFor = transcribing::percentFor,
                )
            }

            if (groups.failed.isNotEmpty()) {
                item { CvSectionHeader(text = stringResource(R.string.transcripts_failed_header)) }
                item {
                    Text(
                        text = stringResource(R.string.transcripts_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                transcriptRows(
                    entries = groups.failed,
                    byName = byName,
                    // The whole card retries, as well as the icon on it: a row that says it failed
                    // and offers one 40dp target to act on is a small target for the one thing the
                    // user opened this heading to do.
                    onOpen = onRetry,
                    onRetry = onRetry,
                    percentFor = { 0 },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.home_transcripts_count, groups.ready.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
                )
            }
            transcriptRows(
                entries = groups.ready,
                byName = byName,
                onOpen = onOpen,
                onRetry = onRetry,
                percentFor = { 0 },
            )
        }
    }
}

/**
 * The rows for audio that is waiting to be read, with the way to start it.
 *
 * Separate from [transcriptRows] because these have no transcript entry to draw a status from — that
 * is the whole reason they are here. The trailing button is therefore always
 * [TranscriptStatus.NONE]'s: offer to transcribe.
 *
 * Tapping the card opens the recording's own screen instead, which is where playing it, sharing it
 * and deleting it already are. A file the user can see but only transcribe is a file they cannot get
 * rid of, and this group exists precisely for the cases where they may want to.
 */
private fun LazyListScope.waitingRows(
    names: List<String>,
    byName: Map<String, RecordingItem>,
    onTranscribe: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    items(names, key = { it }) { displayName ->
        val item = byName[displayName]
        LibraryNameRow(
            title = item?.let { RecordingLabel.of(it) } ?: RecordingLabel.forName(displayName),
            subtitle = item?.displayDate,
            imported = true,
            onOpen = { onOpen(displayName) },
            trailing = {
                TranscriptActionButton(
                    status = TranscriptStatus.NONE,
                    percent = 0,
                    onTranscribe = { onTranscribe(displayName) },
                    onOpen = { onOpen(displayName) },
                    onRetry = { onTranscribe(displayName) },
                )
            },
        )
    }
}

/**
 * One group of rows.
 *
 * A `LazyListScope` extension rather than a composable, so the rows stay individual list items: a
 * composable wrapping them would compose every row in the group at once, which is the whole point of
 * a lazy list on a library that can run to thousands of calls.
 *
 * Keyed on the display name rather than the entry, because the entry's state changes as a
 * transcription progresses and a key that changed would throw the row away and rebuild it.
 */
private fun LazyListScope.transcriptRows(
    entries: List<TranscriptEntry>,
    byName: Map<String, RecordingItem>,
    onOpen: ((String) -> Unit)?,
    onRetry: (String) -> Unit,
    percentFor: (String) -> Int,
) {
    items(entries, key = { it.displayName }) { entry ->
        // Null when the transcript has outlived its recording, which is rare but real: the two are
        // separate databases and the delete cascade is called by hand. The row is still drawn — see
        // TranscriptsPage.group for why dropping it made the hub's count a lie — with the file's own
        // name and no date, so what is missing shows rather than being papered over.
        val item = byName[entry.displayName]
        val status = TranscriptStatus.of(entry.state)
        LibraryNameRow(
            title = item?.let { RecordingLabel.of(it) } ?: RecordingLabel.forName(entry.displayName),
            subtitle = item?.displayDate,
            // Read from the NAME, not from the row: a transcript can outlive its recording, and an
            // import that has been deleted is still an import — saying nothing would make it read as
            // a call whose details all failed to parse.
            imported = ImportedRecording.isImported(entry.displayName),
            onOpen = onOpen?.let { open -> { open(entry.displayName) } },
            // Only where the row has a state worth drawing. A finished transcript needs no icon: the
            // card is the affordance, and an "open" button on a card that opens says it twice.
            trailing = if (status == TranscriptStatus.DONE) null else {
                {
                    TranscriptActionButton(
                        status = status,
                        percent = percentFor(entry.displayName),
                        onTranscribe = { onRetry(entry.displayName) },
                        onOpen = { onOpen?.invoke(entry.displayName) },
                        onRetry = { onRetry(entry.displayName) },
                    )
                }
            },
        )
    }
}

/**
 * The way in for a file CallVault did not record.
 *
 * A card rather than an icon in the bar: this is the one action on the page that creates something,
 * it needs a sentence to explain what it is for, and a glyph in a row of glyphs would be read as
 * another way to filter the list. It sits at the top on purpose — under the search and settings
 * actions, above everything the page is otherwise listing.
 *
 * While a copy is running the card says so and stops taking taps. The copy and the decode check are
 * not instant for a long recording, and a second picker raised over the first would import the same
 * file twice, under two names, with two rows and two transcripts.
 */
@Composable
private fun ImportAudioCard(importing: Boolean, onImport: () -> Unit) {
    CvCard(
        onClick = if (importing) null else onImport,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(IMPORT_GLYPH_SLOT), contentAlignment = Alignment.Center) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        // Stated, not defaulted: several of M3's own roles resolve to CoralDeep in
                        // this scheme, and a red spinner in a teal app reads as a failure.
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.AudioFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (importing) R.string.transcripts_import_working else R.string.transcripts_import_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.transcripts_import_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Matches the trailing slot on a transcript row, so the card's text starts on the same column. */
private val IMPORT_GLYPH_SLOT = 40.dp

/**
 * The heading over what is being transcribed, with the way to stop it.
 *
 * Stop appears only while something is actually running. A queue with nothing in hand — tapped
 * recordings waiting for a worker that has not started — has nothing to stop, and a button that
 * cancels nothing is worse than no button. It opens the queue sheet rather than stopping here, so
 * there is one Stop in the app and it is the one that also releases the row it was working on.
 */
@Composable
private fun QueueHeader(isRunning: Boolean, onOpenQueue: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CvSectionHeader(
            text = stringResource(R.string.transcripts_working_header),
            modifier = Modifier.weight(1f),
        )
        if (isRunning) {
            TextButton(onClick = onOpenQueue) {
                Text(
                    text = stringResource(R.string.transcribing_sheet_stop),
                    // Stated, not defaulted: a TextButton takes its content colour from the primary
                    // role, and this scheme resolves several of M3's own roles to CoralDeep — the
                    // trap already commented on the scroll-to-top button and the progress ring.
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.padding(end = 4.dp))
    }
}
