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
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.SummariesPage
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.RecordingLabel
import com.baba.callvault.ui.common.TranscriptAudio
import com.baba.callvault.ui.common.WorkProgressRing

/**
 * The Summaries page: every call a model has written up, and every one it is writing.
 *
 * ## What it shows, and why in that order
 *
 * The finished summaries are the page, and their count is exactly what the hub's card says —
 * [SummariesPage.Groups.ready] is the same set of rows
 * [com.baba.callvault.data.transcripts.LibraryCounts.summarised] counts, so the card and the page
 * agree by construction rather than by two queries that happen to match. Above them:
 *
 *  - **what is being summarised**, because a run is about ninety seconds of full CPU and gigabytes
 *    of memory that the user asked for, and unlike a transcription nothing anywhere else says so —
 *    there is no pill beside the title and no row state, because a summary has no database row
 *    until it succeeds;
 *  - **what failed**, because nothing retries a failed summary, so without a heading naming them a
 *    run that died is invisible for ever.
 *
 * ## What it does not show
 *
 * **No snippet of the summary.** A summary row carries its whole JSON document, so a first line
 * means reading and parsing every document on every visit to the page — cost that grows with how
 * much the user has summarised, on a screen they open to find something. Same argument, and the
 * same regression shape, as the Transcripts page's missing first line.
 *
 * **No "write it again" on a row.** Deciding a summary needs redoing means having read it and found
 * it wanting, and this list deliberately shows none of the words; a redo button one mis-tap from a
 * row would spend ninety seconds of full CPU replacing something the user never saw was wrong. The
 * button lives under the summary it would replace, which is the only place the judgement can be
 * made. **Stop is the opposite case and is offered here**: it needs no reading, it is always safe,
 * and a run somebody wants stopped is exactly the one they may not be able to find.
 *
 * @param groups     Already grouped; see [SummariesPage] for what is kept and why.
 * @param recordings The catalog, resolved to rows **once for the whole list** rather than per row.
 * @param listState  Hoisted by the caller: this page leaves composition on every section switch and
 *                   whenever a summary is opened over it, taking any place in the list with it.
 * @param onOpen     Opens the reading view — see the call site for why that rather than the
 *                   recording's own screen.
 * @param onStop     Stops whatever the summariser is doing. One stop for the whole queue, because
 *                   that is all there is: the engine serialises on a mutex and runs one at a time.
 */
@Composable
fun SummariesScreen(
    groups: SummariesPage.Groups,
    recordings: List<RecordingItem>,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
) {
    // Resolved once for the whole page rather than per row: a row that looked itself up would put a
    // walk of the recordings list behind every visible line of the screen.
    val byName = recordings.associateBy { it.displayName }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.home_summaries_title),
        onBack = onBack,
        titleTrailing = titleTrailing,
        actions = {
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
                        title = stringResource(R.string.home_summaries_empty_title),
                        hint = stringResource(R.string.home_summaries_empty_hint),
                    )
                }
                return@LazyColumn
            }

            if (groups.working.isNotEmpty()) {
                item { WorkingHeader(onStop = onStop) }
                items(groups.working, key = { "working:" + it.displayName }) { working ->
                    SummaryRow(
                        displayName = working.displayName,
                        item = byName[working.displayName],
                        // Nothing to open while the FIRST summary is being written — and a row that
                        // visibly accepts a tap and does nothing reads as the app having missed it.
                        // A rewrite is a different matter: its earlier summary is still readable,
                        // and that row is in the list below, where it opens.
                        onOpen = null,
                        trailing = { WorkProgressRing(percent = working.percent) },
                    )
                }
            }

            if (groups.failed.isNotEmpty()) {
                item { CvSectionHeader(text = stringResource(R.string.summaries_failed_header)) }
                item {
                    Text(
                        text = stringResource(R.string.summaries_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                summaryRows(groups.failed, byName, onOpen)
            }

            item {
                Text(
                    text = stringResource(R.string.home_summaries_count, groups.ready.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
                )
            }
            summaryRows(groups.ready, byName, onOpen)
        }
    }
}

/**
 * One group of rows.
 *
 * A `LazyListScope` extension rather than a composable, so the rows stay individual list items: a
 * composable wrapping them would compose every row in the group at once, which is the whole point
 * of a lazy list on a library that can run to thousands of calls.
 */
private fun LazyListScope.summaryRows(
    displayNames: List<String>,
    byName: Map<String, RecordingItem>,
    onOpen: (String) -> Unit,
) {
    items(displayNames, key = { it }) { displayName ->
        SummaryRow(
            displayName = displayName,
            item = byName[displayName],
            onOpen = { onOpen(displayName) },
        )
    }
}

/**
 * One row, named the way every other list names the same call.
 *
 * [item] is null when the summary has outlived its recording, which is rare but real: the two are
 * separate databases and the delete cascade is called by hand. **The row is still drawn** — with the
 * file's own name and no date — because the hub's card counts rows in the summaries table and cannot
 * see the recordings catalog, so a row dropped here is a number the user can catch the app lying
 * about by counting. It is also the honest reading of what it is: the summary really is still there
 * and still readable, and only the audio has gone.
 */
@Composable
private fun SummaryRow(
    displayName: String,
    item: RecordingItem?,
    onOpen: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) = LibraryNameRow(
    title = item?.let { RecordingLabel.of(it) } ?: RecordingLabel.forName(displayName),
    subtitle = item?.displayDate,
    // A missing row is the audio being gone, which is what the badge says first: it changes what the
    // row can do. Where the audio IS there, "imported" is read from the NAME rather than from the
    // row, because saying nothing would make an import read as a call whose details all failed to
    // parse. One badge, never two — see TranscriptAudio.badgeFor.
    badge = TranscriptAudio.badgeFor(
        hasAudio = item != null,
        isImported = ImportedRecording.isImported(displayName),
    ),
    onOpen = onOpen,
    trailing = trailing,
)

/**
 * The heading over what is being summarised, with the way to stop it.
 *
 * Stop is always offered here, where the Transcripts page offers it only while a worker is actually
 * running. The difference is what the button does: this one cancels the unique work as well as
 * aborting the engine, so it stops a queued summary as surely as a running one — and a summary that
 * is merely queued is still a phone about to spend ninety seconds at full CPU.
 */
@Composable
private fun WorkingHeader(onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CvSectionHeader(
            text = stringResource(R.string.summaries_working_header),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) {
            Text(
                text = stringResource(R.string.summary_card_stop),
                // Stated, not defaulted: a TextButton takes its content colour from the primary
                // role, and this scheme resolves several of M3's own roles to CoralDeep — the trap
                // already commented on the scroll-to-top button and the progress ring.
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.padding(end = 4.dp))
    }
}
