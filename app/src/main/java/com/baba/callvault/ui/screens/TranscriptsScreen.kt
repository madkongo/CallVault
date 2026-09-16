/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.TranscriptStatus
import com.baba.callvault.data.transcripts.TranscriptsPage
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.ui.common.BidiText
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
            if (groups.isEmpty) {
                item {
                    LibrarySectionEmpty(
                        title = stringResource(R.string.home_transcripts_empty_title),
                        hint = stringResource(R.string.home_transcripts_empty_hint),
                    )
                }
                return@LazyColumn
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
            title = item?.let { RecordingLabel.of(it) } ?: BidiText.isolate(entry.displayName),
            subtitle = item?.displayDate,
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
