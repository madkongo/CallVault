/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.ImportedBadge
import com.baba.callvault.ui.common.TextOnlyBadge
import com.baba.callvault.ui.common.TranscriptAudio

/**
 * The row every library list is built from.
 *
 * Shared by Transcripts and Summaries rather than copied into each, so a call carries one label
 * everywhere and the things this row gets right — the bidi isolation, the fixed trailing slot, the
 * "imported" badge — cannot be remembered in one list and forgotten in the next.
 *
 * **It takes a name, not a catalog row, and that is the point.** A transcript or a summary can
 * outlive its recording — the two are separate databases and the delete cascade is called by hand —
 * and such a row is still worth drawing, because the text is still readable. The caller passes what
 * it knows: the file's own name and no date rather than an invented one. What is missing is the
 * audio, which the badge now says outright rather than leaving to be inferred from a missing date.
 */
@Composable
internal fun LibraryNameRow(
    title: String,
    subtitle: String?,
    /**
     * The one badge beside the title. Here rather than at each call site so that Transcripts and
     * Summaries cannot disagree about what a row says, and so that the next list built on this row
     * gets it without anyone remembering.
     *
     * One, never two: the trailing slot is a fixed width and the title already ellipsises, so a
     * second pill would be paid for out of the name. [TranscriptAudio.badgeFor] decides which.
     */
    badge: TranscriptAudio.RowBadge = TranscriptAudio.RowBadge.None,
    onOpen: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * True while the page is in multi-select, whether or not THIS row can be picked.
     *
     * A row that cannot be picked goes inert for the duration rather than keeping its ordinary tap.
     * The alternative was measured against the gesture the page is in: while building a selection,
     * a tap that started a transcription — or opened a screen — would be the app doing something
     * nobody asked for, out of the middle of a sweep down a list.
     */
    selectionMode: Boolean = false,
    selected: Boolean = false,
    /** Null on a row that cannot be selected, which is also what makes long-press do nothing there. */
    onToggleSelected: (() -> Unit)? = null,
) {
    val selectable = onToggleSelected != null

    CvCard(
        onClick = when {
            selectionMode && selectable -> onToggleSelected
            selectionMode -> null
            else -> onOpen
        },
        // Long-press is what ENTERS selection, so it is live whether or not selection is already on
        // — the same grammar the recordings list uses, and the one the platform trains people in.
        onLongClick = onToggleSelected,
        color = if (selected) {
            // primaryContainer, NOT secondaryContainer: the secondary role in this theme is
            // CoralDeep, so a selected row came out maroon on the recordings list and read as an
            // error or a pending deletion. The note is repeated here because the trap is the theme's,
            // not that screen's.
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
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
                    when (badge) {
                        TranscriptAudio.RowBadge.None -> Unit
                        TranscriptAudio.RowBadge.Imported -> {
                            Spacer(Modifier.width(8.dp))
                            ImportedBadge()
                        }
                        TranscriptAudio.RowBadge.TextOnly -> {
                            Spacer(Modifier.width(8.dp))
                            TextOnlyBadge()
                        }
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
            // The tick takes the slot while selecting, so a row shows what a tap would do to it
            // rather than an action it is no longer offering.
            val slot: (@Composable () -> Unit)? = when {
                selectionMode && selectable -> ({ SelectionTick(selected = selected) })
                selectionMode -> null
                else -> trailing
            }
            if (slot != null) {
                Spacer(Modifier.width(12.dp))
                Box(modifier = Modifier.size(TRAILING_SLOT), contentAlignment = Alignment.Center) {
                    slot()
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

/** Whether this row is in the selection, drawn where its action would otherwise be. */
@Composable
private fun SelectionTick(selected: Boolean) {
    Icon(
        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        contentDescription = null,
        // Stated: the teal the recordings list ticks with, not a role that resolves to CoralDeep.
        tint = if (selected) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

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
