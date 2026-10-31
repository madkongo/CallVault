/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.R

/**
 * The Previous / Next cards under the transcript (#27, mirror176): the call either side of this one in
 * the list it was opened from, by name and date, so you see where a tap goes before you tap.
 *
 * Draws nothing when there is no neighbour on either side. A missing side keeps its half empty, so
 * Next does not jump to the left edge on the first call of the list.
 */
@Composable
internal fun TranscriptCallCards(
    previous: TranscriptNeighbour?,
    next: TranscriptNeighbour?,
    onOpen: (String) -> Unit,
) {
    if (previous == null && next == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (previous != null) {
            CallCard(previous, isNext = false, onOpen = onOpen, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (next != null) {
            CallCard(next, isNext = true, onOpen = onOpen, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CallCard(
    call: TranscriptNeighbour,
    isNext: Boolean,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedCard(onClick = { onOpen(call.displayName) }, modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isNext) ArrowIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = if (isNext) Alignment.End else Alignment.Start,
            ) {
                Text(
                    text = stringResource(if (isNext) R.string.transcript_next_call else R.string.transcript_previous_call),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = call.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (isNext) TextAlign.End else TextAlign.Start,
                )
                if (call.date != null) {
                    Text(
                        text = call.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isNext) ArrowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight)
        }
    }
}

@Composable
private fun ArrowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
}

/**
 * The one "Follow playback" switch (#27). On lights the playing line and keeps it in view; off lights
 * nothing and never scrolls on its own, so a line you are reading stays where it is.
 */
@Composable
internal fun FollowPlaybackChip(follow: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = follow,
        onClick = { onChange(!follow) },
        label = { Text(stringResource(R.string.transcript_follow_playback)) },
        leadingIcon = if (follow) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        // Stated outright: M3's default selected container renders coral in this theme.
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
