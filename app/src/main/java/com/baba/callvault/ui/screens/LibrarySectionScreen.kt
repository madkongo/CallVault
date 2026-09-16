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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
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
import com.baba.callvault.ui.common.ImportedBadge
import com.baba.callvault.ui.common.RecordingLabel

/**
 * The Summaries section, as the hub's card opens it today.
 *
 * Deliberately the smallest version that is not a dead end — a card that opened a "coming soon" page
 * would be worse than no card. Transcripts has outgrown this and has [TranscriptsScreen] of its own;
 * Summaries gets the same treatment in Phase 5, and until then this is still a real list of real
 * recordings that opens the right thing.
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

/**
 * One recording, named the way the recordings list names it so the same call reads the same here.
 *
 * Shared with the Transcripts page rather than copied there, so a call carries one label everywhere
 * and the bidi isolation below cannot be remembered in one list and forgotten in the next.
 *
 * @param onOpen  What a tap does, or null for a row that is only telling the user something — a
 *                transcription still running has nothing to open, and a card that visibly accepts a
 *                tap and then does nothing reads as the app having missed it.
 * @param trailing Drawn at the end of the row: the transcript action button, where the row has a
 *                state worth showing. Nothing by default.
 */
@Composable
internal fun LibrarySectionRow(
    item: RecordingItem,
    onOpen: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) = LibraryNameRow(
    // BidiText.isolate, not the raw name: a Hebrew or Arabic contact next to a Latin-digit
    // timestamp reorders the whole line without it.
    title = RecordingLabel.of(item) ?: BidiText.isolate(item.displayName),
    subtitle = item.displayDate,
    imported = item.isImported,
    onOpen = onOpen,
    trailing = trailing,
)

/**
 * The same row, for something the recordings catalog cannot name.
 *
 * A transcript can outlive its recording — the two are separate databases and the delete cascade is
 * called by hand — and such a row is still worth drawing, because the text is still readable. It is
 * drawn with the file's own name and no date rather than with an invented one: what is missing is
 * the audio, and a row that quietly borrowed a date from somewhere would hide that.
 */
@Composable
internal fun LibraryNameRow(
    title: String,
    subtitle: String?,
    /**
     * Draws the "imported" badge beside the title. Here rather than at each call site so that
     * Transcripts and Summaries cannot disagree about whether a row says where it came from — and
     * so that the next list built on this row gets it without anyone remembering.
     */
    imported: Boolean = false,
    onOpen: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    CvCard(onClick = onOpen, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // weight(1f, fill = false) so the badge keeps its width and the TITLE is
                        // what ellipsises. The other way round the badge would be the thing that
                        // truncated, and half a word saying where a row came from says nothing.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (imported) {
                        Spacer(Modifier.width(8.dp))
                        ImportedBadge()
                    }
                }
                subtitle?.let { line ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.size(TRAILING_SLOT), contentAlignment = Alignment.Center) {
                    trailing()
                }
            }
        }
    }
}

/**
 * The trailing slot's size, fixed rather than wrapped.
 *
 * TranscriptActionButton is an icon button at one size and a progress ring at another, and a slot
 * that measured its content would move the row's text sideways every time a transcription started or
 * finished — in a list where the rows above it are doing the same.
 */
private val TRAILING_SLOT = 40.dp

/** Says what would put something here, rather than only that there is nothing. */
@Composable
internal fun LibrarySectionEmpty(title: String, hint: String) {
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
