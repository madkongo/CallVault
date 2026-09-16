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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.ui.common.BidiText
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.RecordingLabel

/**
 * The Transcripts and Summaries sections, as the hub's cards open them today.
 *
 * One composable for both because they differ only in their words and in what a tap opens: a list of
 * the recordings that have the thing the section is named after, newest first. That is deliberately
 * the smallest version that is not a dead end — a card that opened a "coming soon" page would be
 * worse than no card. Phase 3 gives Transcripts its own screen, with the reading view, the queue and
 * the search that belong to it; Phase 5 does the same for Summaries.
 *
 * [names] comes from the transcripts database and [recordings] from the catalog, so a name with no
 * row is normal rather than exceptional — a recording can be deleted while its transcript is still
 * being cleaned up, and the two are separate databases with no foreign key between them. Such a name
 * is skipped rather than rendered as an unlabelled row nothing can open.
 *
 * @param listState Hoisted by the caller, like the recordings list's own: this screen leaves
 *                  composition whenever the user opens a recording or steps back to the hub, and
 *                  state remembered inside it would take the reader's place in the list with it.
 */
@Composable
fun LibrarySectionScreen(
    title: String,
    countLabel: String,
    names: List<String>,
    recordings: List<RecordingItem>,
    emptyTitle: String,
    emptyHint: String,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolved once for the whole list rather than per row: every row would otherwise walk the
    // recordings list looking for itself, which is the shape of the list-load regression.
    val byName = recordings.associateBy { it.displayName }
    val rows = names.mapNotNull { byName[it] }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = title,
        onBack = onBack,
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
            if (rows.isEmpty()) {
                item { LibrarySectionEmpty(title = emptyTitle, hint = emptyHint) }
            } else {
                item {
                    Text(
                        text = countLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                items(rows, key = { it.uri.toString() }) { item ->
                    LibrarySectionRow(item = item, onOpen = { onOpen(item.displayName) })
                }
            }
        }
    }
}

/** One recording, named the way the recordings list names it so the same call reads the same here. */
@Composable
private fun LibrarySectionRow(item: RecordingItem, onOpen: () -> Unit) {
    CvCard(onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            // BidiText.isolate, not the raw name: a Hebrew or Arabic contact next to a
            // Latin-digit timestamp reorders the whole line without it.
            text = RecordingLabel.of(item) ?: BidiText.isolate(item.displayName),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.displayDate?.let { date ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Says what would put something here, rather than only that there is nothing. */
@Composable
private fun LibrarySectionEmpty(title: String, hint: String) {
    CvCard(contentPadding = PaddingValues(20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
