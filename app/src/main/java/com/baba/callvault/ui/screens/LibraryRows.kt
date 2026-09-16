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
 * audio, and a row that quietly borrowed a date from somewhere would hide that.
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
